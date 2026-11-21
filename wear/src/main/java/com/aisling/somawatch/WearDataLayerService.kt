package com.aisling.somawatch

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WearDataLayerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        val payload = String(messageEvent.data, Charsets.UTF_8)
        Log.i(TAG, "Message received on path=${messageEvent.path}: $payload")

        when (messageEvent.path) {
            "/soma/ping" -> {
                vibrate(120)
                SomaWearState.recordPing(payload.ifEmpty { "Телефон" })
                scope.launch {
                    runCatching {
                        val reply = "Soma Watch (v${BuildConfig.VERSION_NAME}) на связи!"
                        Wearable.getMessageClient(this@WearDataLayerService)
                            .sendMessage(
                                messageEvent.sourceNodeId,
                                "/soma/pong",
                                reply.toByteArray(Charsets.UTF_8)
                            ).await()
                    }
                }
            }
            "/soma/ota_status" -> {
                SomaWearState.updateOtaProgress(payload)
            }
            "/soma/jules_result" -> {
                vibrate(160)
                SomaWearState.recordJulesResult(payload)
            }
            "/soma/voice_transcribed" -> {
                vibrate(90)
                if (payload.startsWith("ERR:")) {
                    SomaWearState.updateStatus(payload.removePrefix("ERR:").trim())
                } else {
                    SomaWearState.updateStatus("Gemini расшифровал:")
                    SomaWearState.setPendingVoicePrompt(payload)
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path == "/soma/ota_apk") {
            if (!isReceivingOta.compareAndSet(false, true)) {
                Log.w(TAG, "Ignoring duplicate OTA channel while transfer is already active")
                return
            }

            Log.i(TAG, "Receiving OTA APK over ChannelClient...")
            vibrate(100)
            SomaWearState.setOtaBusy(true)
            SomaWearState.updateOtaProgress("Приём OTA по Bluetooth...")

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SomaWatch:OtaReceive")
            wakeLock.acquire(3 * 60 * 1000L)

            scope.launch {
                try {
                    val otaFile = File(cacheDir, "soma-wear-ota.apk")
                    if (otaFile.exists()) otaFile.delete()

                    val inputStream = Wearable.getChannelClient(this@WearDataLayerService)
                        .getInputStream(channel)
                        .await()

                    var totalBytes = 0L
                    var lastReportedKb = 0L
                    inputStream.use { input ->
                        otaFile.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                totalBytes += read
                                val kb = totalBytes / 1024
                                if (kb - lastReportedKb >= 64) {
                                    lastReportedKb = kb
                                    val mb = totalBytes / (1024.0 * 1024.0)
                                    SomaWearState.updateOtaProgress(
                                        String.format("Загрузка OTA: %.1f МБ", mb)
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    Log.i(TAG, "OTA APK received ($totalBytes bytes). Launching installer...")
                    WearOtaInstaller.installApk(this@WearDataLayerService, otaFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed receiving OTA APK over channel", e)
                    SomaWearState.updateOtaProgress("Ошибка приёма OTA: ${e.localizedMessage}")
                } finally {
                    isReceivingOta.set(false)
                    SomaWearState.setOtaBusy(false)
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    private fun vibrate(ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val TAG = "SomaWearDataLayer"
        private val isReceivingOta = AtomicBoolean(false)
    }
}
