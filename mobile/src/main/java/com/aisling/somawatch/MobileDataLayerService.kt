package com.aisling.somawatch

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

class MobileDataLayerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        val payload = String(messageEvent.data, Charsets.UTF_8)
        Log.i(TAG, "Message from watch on ${messageEvent.path}: $payload")

        when (messageEvent.path) {
            "/soma/ping", "/soma/pong" -> {
                SomaMobileState.recordWatchMessage(payload)
            }
            "/soma/debug_hr_raw" -> {
                Log.i(TAG, "WATCH_HR_RAW_PACKET: [$payload]")
            }
            "/soma/voice_prompt" -> {
                SomaMobileState.recordWatchMessage("🎙️ С часов: «$payload»")
                scope.launch {
                    GitHubJulesClient.createJulesIssue(
                        context = this@MobileDataLayerService,
                        promptText = payload,
                        source = "Wrist",
                        replyToNodeId = messageEvent.sourceNodeId
                    )
                }
            }
            "/soma/workout_summary" -> {
                val summary = MobileBiometricsStore.saveWorkoutSummary(this, payload)
                if (summary != null) {
                    val z3 = runCatching { org.json.JSONObject(payload).optInt("sessionZ3Sec", 0) }.getOrDefault(0)
                    val db = SomaHealthDb.get(this)
                    db.insertWorkout(summary, z3)
                    db.writeExportFile(this)
                    SomaMobileState.setHealthDbStats(db.loadStats())
                    SomaMobileState.setLastBioSummary(summary)
                    SomaMobileState.recordWatchMessage(
                        "Тренировка: ${summary.durationSec / 60}м • ср ${summary.avgHr} BPM • Z2 ${summary.weeklyZ2Min}/${summary.weeklyZ2Goal}м"
                    )
                }
            }
            "/soma/telemetry_sample" -> {
                runCatching {
                    val j = org.json.JSONObject(payload)
                    val db = SomaHealthDb.get(this)
                    db.insertTelemetry(
                        timestamp = j.optLong("timestamp", System.currentTimeMillis()),
                        hr = j.optInt("hr", 0),
                        hrvMs = j.optInt("hrvMs", -1).takeIf { it > 0 },
                        zone = j.optInt("zone", 0),
                        noiseDb = j.optInt("db", 0).takeIf { it > 0 }
                    )
                    db.writeExportFile(this)
                    SomaMobileState.setHealthDbStats(db.loadStats())
                }
            }
            "/soma/passive_hrr" -> {
                runCatching {
                    val j = org.json.JSONObject(payload)
                    val peak = j.optInt("peakHr", 0)
                    val hrr1 = j.optInt("hrr1", -1).takeIf { it >= 0 }
                    val hrr2 = j.optInt("hrr2", -1).takeIf { it >= 0 }
                    val db = SomaHealthDb.get(this)
                    db.insertHrrEvent(j.optLong("timestamp", System.currentTimeMillis()), "passive", peak, hrr1, hrr2)
                    db.writeExportFile(this)
                    SomaMobileState.setHealthDbStats(db.loadStats())
                    SomaMobileState.recordWatchMessage("Фоновый HRR (пик $peak): -${hrr1 ?: "?"} / -${hrr2 ?: "?"} BPM")
                }
            }
            "/soma/noise_event" -> {
                runCatching {
                    val j = org.json.JSONObject(payload)
                    val dbVal = j.optInt("db", 0)
                    val thr = j.optInt("thresholdDb", 85)
                    val db = SomaHealthDb.get(this)
                    db.insertNoiseEvent(j.optLong("timestamp", System.currentTimeMillis()), dbVal, thr)
                    db.writeExportFile(this)
                    SomaMobileState.setHealthDbStats(db.loadStats())
                    SomaMobileState.recordWatchMessage("Алерт шума: $dbVal дБ (порог $thr дБ)")
                }
            }
            "/soma/check_ota_version" -> {
                val watchVersionCode = payload.trim().toIntOrNull() ?: 0
                scope.launch {
                    val release = GitHubOtaManager.checkLatestRelease()
                    val reply = if (release != null && release.wearApkUrl != null && release.remoteVersionCode > watchVersionCode) {
                        "${release.remoteVersionCode}|${release.tagName}"
                    } else {
                        "UP_TO_DATE"
                    }
                    runCatching {
                        Wearable.getMessageClient(this@MobileDataLayerService)
                            .sendMessage(
                                messageEvent.sourceNodeId,
                                "/soma/ota_available",
                                reply.toByteArray(Charsets.UTF_8)
                            ).await()
                    }
                }
            }
            "/soma/request_ota" -> {
                if (SomaMobileState.isBusy.value) {
                    Log.w(TAG, "Ignoring /soma/request_ota because OTA transfer is already running")
                    return
                }
                val watchVersionCode = payload.trim().toIntOrNull() ?: 0
                SomaMobileState.recordWatchMessage("Часы запросили обновление OTA (текущий #$watchVersionCode)")
                scope.launch {
                    val release = GitHubOtaManager.checkLatestRelease()
                    if (release?.wearApkUrl != null) {
                        if (watchVersionCode > 0 && release.remoteVersionCode <= watchVersionCode) {
                            Wearable.getMessageClient(this@MobileDataLayerService)
                                .sendMessage(
                                    messageEvent.sourceNodeId,
                                    "/soma/ota_status",
                                    "DONE:Актуальная версия ✓".toByteArray(Charsets.UTF_8)
                                ).await()
                            Wearable.getMessageClient(this@MobileDataLayerService)
                                .sendMessage(
                                    messageEvent.sourceNodeId,
                                    "/soma/ota_available",
                                    "UP_TO_DATE".toByteArray(Charsets.UTF_8)
                                ).await()
                        } else {
                            GitHubOtaManager.pushWearOtaToWatch(
                                context = this@MobileDataLayerService,
                                wearApkUrl = release.wearApkUrl,
                                versionLabel = release.tagName
                            )
                        }
                    } else {
                        val msg = "DONE:Релиз не найден в ${BuildConfig.GITHUB_REPO}"
                        Wearable.getMessageClient(this@MobileDataLayerService)
                            .sendMessage(
                                messageEvent.sourceNodeId,
                                "/soma/ota_status",
                                msg.toByteArray(Charsets.UTF_8)
                            ).await()
                    }
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path == "/soma/voice_audio") {
            Log.i(TAG, "Receiving voice audio from watch for Gemini transcription...")
            SomaMobileState.setJulesStatus("Приём аудио с Galaxy Watch 8...")
            scope.launch {
                val audioFile = File(cacheDir, "wrist_voice_prompt.m4a")
                try {
                    if (audioFile.exists()) audioFile.delete()
                    val inputStream = Wearable.getChannelClient(this@MobileDataLayerService)
                        .getInputStream(channel)
                        .await()
                    inputStream.use { input ->
                        audioFile.outputStream().use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }

                    val sizeKb = audioFile.length() / 1024
                    SomaMobileState.setJulesStatus("Аудио ($sizeKb KB) → Gemini 2.5 Flash...")
                    val result = GeminiVoiceClient.transcribeAudio(this@MobileDataLayerService, audioFile)

                    val replyPayload = result.fold(
                        onSuccess = { text ->
                            SomaMobileState.recordWatchMessage("Gemini: «$text»")
                            SomaMobileState.setJulesStatus("Расшифровано → ждём подтверждения 🚀 на часах")
                            text
                        },
                        onFailure = { err ->
                            val msg = err.localizedMessage ?: "Ошибка Gemini"
                            SomaMobileState.setJulesStatus("Ошибка Gemini: $msg")
                            "ERR:$msg"
                        }
                    )

                    Wearable.getMessageClient(this@MobileDataLayerService)
                        .sendMessage(
                            channel.nodeId,
                            "/soma/voice_transcribed",
                            replyPayload.toByteArray(Charsets.UTF_8)
                        ).await()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed receiving/transcribing watch audio", e)
                    SomaMobileState.setJulesStatus("Ошибка аудио: ${e.localizedMessage}")
                    runCatching {
                        Wearable.getMessageClient(this@MobileDataLayerService)
                            .sendMessage(
                                channel.nodeId,
                                "/soma/voice_transcribed",
                                "ERR:${e.localizedMessage}".toByteArray(Charsets.UTF_8)
                            ).await()
                    }
                } finally {
                    runCatching { audioFile.delete() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "SomaMobileDataLayer"
    }
}
