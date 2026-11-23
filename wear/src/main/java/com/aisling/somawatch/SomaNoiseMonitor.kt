package com.aisling.somawatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
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
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NoiseUiState(
    val currentDb: Int = 0,
    val peakDbToday: Int = 0,
    val isMonitoring: Boolean = false,
    val isSamplingNow: Boolean = false,
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
    private var backgroundJob: Job? = null
    private var foregroundJob: Job? = null
    private var lastAlertTimeMs = 0L
    private var lastComplicationPushMs = 0L
    private var lastComplicationDb = 0

    @Volatile
    var isVoiceRecordingActive: Boolean = false

    private val _state = MutableStateFlow(NoiseUiState())
    val state: StateFlow<NoiseUiState> = _state

    fun init(context: Context) {
        val ctx = context.applicationContext
        val threshold = SomaBiometricsStore.getNoiseThresholdDb(ctx)
        val bgOn = SomaBiometricsStore.isNoiseAlertEnabled(ctx)
        val savedDb = SomaBiometricsStore.getLastNoiseDb(ctx)
        val savedPeak = SomaBiometricsStore.getPeakNoiseDbToday(ctx)
        _state.update {
            it.copy(
                currentDb = if (it.currentDb > 0) it.currentDb else savedDb,
                peakDbToday = maxOf(it.peakDbToday, savedPeak),
                thresholdDb = threshold,
                isMonitoring = bgOn
            )
        }
        if (bgOn && backgroundJob?.isActive != true) {
            startBackgroundMonitoring(ctx)
        }
    }

    fun updateThreshold(context: Context, thresholdDb: Int) {
        val clamped = thresholdDb.coerceIn(60, 110)
        SomaBiometricsStore.setNoiseThresholdDb(context, clamped)
        _state.update { it.copy(thresholdDb = clamped) }
        requestComplicationUpdate(context.applicationContext, force = true)
    }

    fun toggleMonitoring(context: Context, enable: Boolean) {
        if (enable) {
            startBackgroundMonitoring(context)
        } else {
            stopBackgroundMonitoring(context)
        }
    }

    /**
     * Live foreground preview while the Watch screen is visible:
     * takes an immediate 0.6s measurement, then refreshes every 5s.
     * Stops automatically in onStop() so zero battery is used off-screen.
     */
    fun startForegroundPreview(context: Context) {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            while (isActive) {
                if (!isVoiceRecordingActive) {
                    sampleAndUpdate(ctx, durationMs = 600L, triggerAlerts = _state.value.isMonitoring)
                }
                delay(5000L)
            }
        }
    }

    fun stopForegroundPreview() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    fun startBackgroundMonitoring(context: Context) {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; skipping noise monitor")
            return
        }

        val threshold = SomaBiometricsStore.getNoiseThresholdDb(ctx)
        SomaBiometricsStore.setNoiseAlertEnabled(ctx, true)
        _state.update { it.copy(isMonitoring = true, thresholdDb = threshold) }

        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            while (isActive) {
                if (!isVoiceRecordingActive) {
                    sampleAndUpdate(ctx, durationMs = 1400L, triggerAlerts = true)
                }
                delay(45_000L)
            }
        }
    }

    fun stopBackgroundMonitoring(context: Context) {
        backgroundJob?.cancel()
        backgroundJob = null
        SomaBiometricsStore.setNoiseAlertEnabled(context.applicationContext, false)
        _state.update { it.copy(isMonitoring = false, isLoudAlert = false) }
    }

    fun measureOnce(context: Context) {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        scope.launch {
            if (!isVoiceRecordingActive) {
                sampleAndUpdate(ctx, durationMs = 900L, triggerAlerts = true)
            }
        }
    }

    private suspend fun sampleAndUpdate(ctx: Context, durationMs: Long, triggerAlerts: Boolean) {
        _state.update { it.copy(isSamplingNow = true) }
        try {
            val db = measureSampleDb(durationMs) ?: return
            val threshold = _state.value.thresholdDb
            val isLoud = db >= threshold
            SomaBiometricsStore.saveNoiseReading(ctx, db, System.currentTimeMillis())
            _state.update { cur ->
                cur.copy(
                    currentDb = db,
                    peakDbToday = maxOf(cur.peakDbToday, db),
                    isLoudAlert = isLoud
                )
            }
            requestComplicationUpdate(ctx, db = db)

            if (isLoud && triggerAlerts) {
                val confirmDb = measureSampleDb(2500L) ?: db
                SomaBiometricsStore.saveNoiseReading(ctx, confirmDb, System.currentTimeMillis())
                if (confirmDb >= threshold) {
                    _state.update { cur ->
                        cur.copy(
                            currentDb = confirmDb,
                            peakDbToday = maxOf(cur.peakDbToday, confirmDb),
                            isLoudAlert = true
                        )
                    }
                    requestComplicationUpdate(ctx, db = confirmDb, force = true)
                    maybeTriggerLoudAlert(ctx, confirmDb, threshold)
                } else {
                    _state.update { cur ->
                        cur.copy(
                            currentDb = confirmDb,
                            peakDbToday = maxOf(cur.peakDbToday, confirmDb),
                            isLoudAlert = false
                        )
                    }
                    requestComplicationUpdate(ctx, db = confirmDb)
                }
            }
        } finally {
            _state.update { it.copy(isSamplingNow = false) }
        }
    }

    private fun requestComplicationUpdate(ctx: Context, db: Int = _state.value.currentDb, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && abs(db - lastComplicationDb) < 2 && now - lastComplicationPushMs < 15_000L) {
            return
        }
        lastComplicationDb = db
        lastComplicationPushMs = now
        runCatching {
            val requester = ComplicationDataSourceUpdateRequester.create(
                ctx,
                ComponentName(ctx, NoiseComplicationService::class.java)
            )
            requester.requestUpdateAll()
        }
    }

    private fun measureSampleDb(durationMs: Long): Int? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        for (source in sources) {
            val res = tryMeasureWithSource(source, durationMs)
            if (res != null) return res
        }
        return null
    }

    private fun tryMeasureWithSource(audioSource: Int, durationMs: Long): Int? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        var audioRecord: AudioRecord? = null
        return try {
            audioRecord = AudioRecord(
                audioSource,
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

            while (System.currentTimeMillis() < endAt && !isVoiceRecordingActive) {
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
            if (totalSamples == 0L || sumSq <= 0.0) return null

            val rms = sqrt(sumSq / totalSamples).coerceAtLeast(1.0)
            val dbfs = 20.0 * log10(rms / 32767.0)
            (dbfs + 115.0).roundToInt().coerceIn(28, 115)
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord source=$audioSource failed: ${e.localizedMessage}")
            null
        } finally {
            runCatching { audioRecord?.release() }
        }
    }

    private suspend fun maybeTriggerLoudAlert(ctx: Context, db: Int, thresholdDb: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTimeMs < ALERT_COOLDOWN_MS) return
        lastAlertTimeMs = now

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
