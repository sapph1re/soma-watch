package com.aisling.somawatch

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
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
            "/soma/voice_audio_bytes" -> {
                val rawBytes = messageEvent.data
                val nodeId = messageEvent.sourceNodeId
                scope.launch {
                    val audioFile = File(cacheDir, "wrist_msg_${System.currentTimeMillis()}.m4a")
                    try {
                        audioFile.writeBytes(rawBytes)
                        transcribeAndReply(audioFile, nodeId)
                    } finally {
                        runCatching { audioFile.delete() }
                    }
                }
            }
            "/soma/recovery_bout" -> {
                runCatching {
                    val j = org.json.JSONObject(payload)
                    val db = SomaHealthDb.get(this)
                    db.insertRecoveryBout(
                        timestamp = j.optLong("timestamp", System.currentTimeMillis()),
                        peakHr = j.optInt("peakHr", 0),
                        endHr = j.optInt("endHr", 0),
                        dropBpm = j.optInt("dropBpm", 0),
                        observedSec = j.optInt("observedSec", 0),
                        tauSec = j.optInt("tauSec", 0),
                        slopeBpmMin = j.optInt("slopeBpmPerMin", 0),
                        projectedHrr60 = j.optInt("projectedHrr60", 0),
                        confidence = j.optString("confidence", "extrapolated_t30"),
                        restType = j.optString("restType", "passive"),
                        context = j.optString("context", "daily")
                    )
                    db.writeExportFile(this)
                    SomaMobileState.setHealthDbStats(db.loadStats())
                    SomaMobileState.recordWatchMessage(
                        "Recovery (${j.optString("context")}): τ=${j.optInt("tauSec")}s, eHRR60=-${j.optInt("projectedHrr60")} bpm"
                    )
                }
            }
            "/soma/voice_prompt" -> {
                SomaMobileState.recordWatchMessage("🎙️ From watch: \"$payload\"")
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
                        "Workout: ${summary.durationSec / 60}m • Avg ${summary.avgHr} BPM • Z2 ${summary.weeklyZ2Min}/${summary.weeklyZ2Goal}m"
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
                    SomaMobileState.recordWatchMessage("Passive HRR (peak $peak): -${hrr1 ?: "?"} / -${hrr2 ?: "?"} BPM")
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
                    SomaMobileState.recordWatchMessage("Noise alert: $dbVal dB (limit $thr dB)")
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
                SomaMobileState.recordWatchMessage("Watch requested OTA update (current #$watchVersionCode)")
                scope.launch {
                    val release = GitHubOtaManager.checkLatestRelease()
                    if (release?.wearApkUrl != null) {
                        if (watchVersionCode > 0 && release.remoteVersionCode <= watchVersionCode) {
                            Wearable.getMessageClient(this@MobileDataLayerService)
                                .sendMessage(
                                    messageEvent.sourceNodeId,
                                    "/soma/ota_status",
                                    "DONE:Up to date ✓".toByteArray(Charsets.UTF_8)
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
                        val msg = "DONE:Release not found in ${BuildConfig.GITHUB_REPO}"
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

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/soma/voice_asset") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val asset = dataMap.getAsset("audio") ?: continue
                val sourceNodeId = event.dataItem.uri.host
                scope.launch {
                    val audioFile = File(cacheDir, "wrist_asset_${System.currentTimeMillis()}.m4a")
                    try {
                        SomaMobileState.setJulesStatus("Receiving voice asset from Galaxy Watch 8...")
                        val fdResult = Wearable.getDataClient(this@MobileDataLayerService)
                            .getFdForAsset(asset)
                            .await()
                        fdResult.inputStream.use { input ->
                            audioFile.outputStream().use { output ->
                                input.copyTo(output)
                                output.flush()
                            }
                        }
                        transcribeAndReply(audioFile, sourceNodeId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed reading voice asset", e)
                        SomaMobileState.setJulesStatus("Audio asset error: ${e.localizedMessage}")
                        replyToWatch(sourceNodeId, "ERR:${e.localizedMessage}")
                    } finally {
                        runCatching { audioFile.delete() }
                    }
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        super.onChannelOpened(channel)
        if (channel.path == "/soma/voice_audio") {
            Log.i(TAG, "Receiving voice audio over ChannelClient...")
            SomaMobileState.setJulesStatus("Receiving audio from Galaxy Watch 8...")
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
                    transcribeAndReply(audioFile, channel.nodeId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed receiving/transcribing watch audio", e)
                    SomaMobileState.setJulesStatus("Audio error: ${e.localizedMessage}")
                    replyToWatch(channel.nodeId, "ERR:${e.localizedMessage}")
                } finally {
                    runCatching { audioFile.delete() }
                }
            }
        }
    }

    private suspend fun transcribeAndReply(audioFile: File, targetNodeId: String?) {
        val sizeKb = (audioFile.length() / 1024).coerceAtLeast(1)
        SomaMobileState.setJulesStatus("Audio ($sizeKb KB) → Gemini 2.5 Flash...")
        val result = GeminiVoiceClient.transcribeAudio(this@MobileDataLayerService, audioFile)
        val replyPayload = result.fold(
            onSuccess = { text ->
                SomaMobileState.recordWatchMessage("Gemini: \"$text\"")
                SomaMobileState.setJulesStatus("Transcribed → awaiting confirmation on watch")
                text
            },
            onFailure = { err ->
                val msg = err.localizedMessage ?: "Gemini error"
                SomaMobileState.setJulesStatus("Gemini error: $msg")
                "ERR:$msg"
            }
        )
        replyToWatch(targetNodeId, replyPayload)
    }

    private suspend fun replyToWatch(nodeId: String?, payload: String) {
        runCatching {
            val targets = if (!nodeId.isNullOrBlank()) {
                listOf(nodeId)
            } else {
                Wearable.getNodeClient(this@MobileDataLayerService).connectedNodes.await().map { it.id }
            }
            targets.forEach { id ->
                Wearable.getMessageClient(this@MobileDataLayerService)
                    .sendMessage(id, "/soma/voice_transcribed", payload.toByteArray(Charsets.UTF_8))
                    .await()
            }
        }
    }

    companion object {
        private const val TAG = "SomaMobileDataLayer"
    }
}
