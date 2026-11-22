package com.aisling.somawatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NoiseUiState(
    val currentDb: Int = 0,
    val peakDbToday: Int = 0,
    val isMonitoring: Boolean = false,
    val thresholdDb: Int = 85,
    val isLoudAlert: Boolean = false
)

object SomaNoiseMonitor {
    private const val TAG = "SomaNoiseMonitor"
    private const val CHANNEL_ID = "soma_noise_alerts"
    private const val NOTIF_ID = 7019
    private const val SAMPLE_RATE = 16000
    private const val ALERT_COOLDOWN_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var lastAlertTimeMs = 0L

    private val _state = MutableStateFlow(NoiseUiState())
    val state: StateFlow<NoiseUiState> = _state

    fun init(context: Context) {
        val threshold = SomaBiometricsStore.getNoiseThresholdDb(context)
        _state.value = _state.value.copy(thresholdDb = threshold)
    }

    fun updateThreshold(context: Context, thresholdDb: Int) {
        val clamped = thresholdDb.coerceIn(60, 110)
        SomaBiometricsStore.setNoiseThresholdDb(context, clamped)
        _state.value = _state.value.copy(thresholdDb = clamped)
    }

    fun toggleMonitoring(context: Context, enable: Boolean) {
        if (enable) {
            startMonitoring(context, foregroundLive = false)
        } else {
            stopMonitoring(context)
        }
    }

    fun startMonitoring(context: Context, foregroundLive: Boolean = false) {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; skipping noise monitor")
            return
        }

        val threshold = SomaBiometricsStore.getNoiseThresholdDb(ctx)
        SomaBiometricsStore.setNoiseAlertEnabled(ctx, true)
        _state.value = _state.value.copy(isMonitoring = true, thresholdDb = threshold)

        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val sampleDurationMs = if (foregroundLive) 600L else 1400L
                val db = measureSampleDb(sampleDurationMs)
                if (db != null) {
                    val cur = _state.value
                    val isLoud = db >= cur.thresholdDb
                    _state.value = cur.copy(
                        currentDb = db,
                        peakDbToday = maxOf(cur.peakDbToday, db),
                        isLoudAlert = isLoud
                    )

                    if (isLoud) {
                        // Confirm sustained noise for 3 seconds (ignores brief claps/doors)
                        val confirmDb = measureSampleDb(3000L) ?: db
                        if (confirmDb >= cur.thresholdDb) {
                            _state.value = _state.value.copy(
                                currentDb = confirmDb,
                                peakDbToday = maxOf(_state.value.peakDbToday, confirmDb),
                                isLoudAlert = true
                            )
                            maybeTriggerLoudAlert(ctx, confirmDb, cur.thresholdDb)
                        } else {
                            _state.value = _state.value.copy(currentDb = confirmDb, isLoudAlert = false)
                        }
                    }
                }
                // Duty-cycle: sleep 45s in background to save battery (~3% duty cycle), or 2.5s in live mode
                delay(if (foregroundLive) 2500L else 45_000L)
            }
        }
    }

    fun stopMonitoring(context: Context) {
        monitorJob?.cancel()
        monitorJob = null
        SomaBiometricsStore.setNoiseAlertEnabled(context.applicationContext, false)
        _state.value = _state.value.copy(isMonitoring = false, isLoudAlert = false)
    }

    /**
     * One-shot measurement when user taps the Noise card without keeping background loop running.
     */
    fun measureOnce(context: Context) {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        scope.launch {
            val db = measureSampleDb(1000L) ?: return@launch
            val cur = _state.value
            val isLoud = db >= cur.thresholdDb
            _state.value = cur.copy(
                currentDb = db,
                peakDbToday = maxOf(cur.peakDbToday, db),
                isLoudAlert = isLoud
            )
            if (isLoud) {
                maybeTriggerLoudAlert(ctx, db, cur.thresholdDb)
            }
        }
    }

    private fun measureSampleDb(durationMs: Long): Int? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        var audioRecord: AudioRecord? = null
        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) return null

            val buffer = ShortArray(minBuf)
            audioRecord.startRecording()

            val endAt = System.currentTimeMillis() + durationMs
            var sumSq = 0.0
            var totalSamples = 0L

            while (System.currentTimeMillis() < endAt) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        val s = buffer[i].toDouble()
                        sumSq += s * s
                    }
                    totalSamples += read
                }
            }

            audioRecord.stop()
            if (totalSamples == 0L) return null

            val rms = sqrt(sumSq / totalSamples).coerceAtLeast(1.0)
            // Calibrated approximation for wearable MEMS microphone (0 dBFS ~ 115 dB SPL)
            val dbfs = 20.0 * log10(rms / 32767.0)
            val approxSpl = (dbfs + 115.0).roundToInt().coerceIn(30, 115)
            approxSpl
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord sample failed: ${e.localizedMessage}")
            null
        } finally {
            runCatching { audioRecord?.release() }
        }
    }

    private suspend fun maybeTriggerLoudAlert(ctx: Context, db: Int, thresholdDb: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTimeMs < ALERT_COOLDOWN_MS) return
        lastAlertTimeMs = now

        // Distinct triple-pulse gentle vibration alert
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90, 90, 140), -1)
            )
        }

        // Post Wear OS loud-environment alert notification
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Алерт громкости Soma",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Громко: $db дБ")
                .setContentText("Порог $thresholdDb дБ превышен — надень беруши?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        }

        // Sync noise event to phone SQLite DB
        runCatching {
            val payload = JSONObject().apply {
                put("timestamp", now)
                put("db", db)
                put("thresholdDb", thresholdDb)
            }
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            nodes.forEach { node ->
                Wearable.getMessageClient(ctx)
                    .sendMessage(node.id, "/soma/noise_event", payload.toString().toByteArray(Charsets.UTF_8))
                    .await()
            }
        }
    }
}
