package com.aisling.somawatch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
            "/soma/request_ota" -> {
                if (SomaMobileState.isBusy.value) {
                    Log.w(TAG, "Ignoring /soma/request_ota because OTA transfer is already running")
                    return
                }
                SomaMobileState.recordWatchMessage("Часы запросили проверку облачного OTA")
                scope.launch {
                    val release = GitHubOtaManager.checkLatestRelease()
                    if (release?.wearApkUrl != null) {
                        GitHubOtaManager.pushWearOtaToWatch(this@MobileDataLayerService, release.wearApkUrl)
                    } else {
                        val msg = "Релиз с wear APK не найден в ${BuildConfig.GITHUB_REPO}"
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

    companion object {
        private const val TAG = "SomaMobileDataLayer"
    }
}
