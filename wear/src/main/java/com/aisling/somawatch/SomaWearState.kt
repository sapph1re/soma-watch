package com.aisling.somawatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AvailableOta(
    val remoteVersionCode: Int,
    val label: String
)

data class OtaUiState(
    val isActive: Boolean = false,
    val bannerText: String? = null,
    val progress: Float? = null,
    val receivedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val versionLabel: String? = null
)

object SomaWearState {
    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _lastSyncInfo = MutableStateFlow("Tap Voice Idea to build OTA")
    val lastSyncInfo: StateFlow<String> = _lastSyncInfo

    private val _otaState = MutableStateFlow(OtaUiState())
    val otaState: StateFlow<OtaUiState> = _otaState

    private val _availableUpdate = MutableStateFlow<AvailableOta?>(null)
    val availableUpdate: StateFlow<AvailableOta?> = _availableUpdate

    private val _pendingVoicePrompt = MutableStateFlow<String?>(null)
    val pendingVoicePrompt: StateFlow<String?> = _pendingVoicePrompt

    fun updateStatus(msg: String) {
        _statusMessage.value = msg
    }

    fun recordPing(from: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastSyncInfo.value = "$from • $time"
        _statusMessage.value = "Ping received"
    }

    fun recordAgentResult(result: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastSyncInfo.value = "$time • $result"
        _statusMessage.value = result
    }

    fun setAvailableUpdate(update: AvailableOta?) {
        _availableUpdate.value = update
        if (update != null && _otaState.value.bannerText != null && !_otaState.value.isActive) {
            _otaState.value = _otaState.value.copy(bannerText = null)
        }
    }

    fun startOtaIndeterminate(message: String, totalBytes: Long = 0L, versionLabel: String? = null) {
        _otaState.value = OtaUiState(
            isActive = true,
            bannerText = message,
            progress = null,
            receivedBytes = 0L,
            totalBytes = totalBytes,
            versionLabel = versionLabel
        )
    }

    fun updateOtaTransfer(receivedBytes: Long, totalBytes: Long) {
        val current = _otaState.value
        val effectiveTotal = if (totalBytes > 0L) totalBytes else current.totalBytes
        val pct = if (effectiveTotal > 0L) {
            (receivedBytes.toFloat() / effectiveTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        val mbDone = receivedBytes / (1024.0 * 1024.0)
        val label = if (effectiveTotal > 0L) {
            val mbTotal = effectiveTotal / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f / %.1f MB", mbDone, mbTotal)
        } else {
            String.format(Locale.US, "%.1f MB", mbDone)
        }
        _otaState.value = current.copy(
            isActive = true,
            bannerText = label,
            progress = pct,
            receivedBytes = receivedBytes,
            totalBytes = effectiveTotal
        )
    }

    fun finishOtaMessage(message: String?) {
        _otaState.value = OtaUiState(
            isActive = false,
            bannerText = message,
            progress = null
        )
    }

    fun setPendingVoicePrompt(text: String?) {
        _pendingVoicePrompt.value = text
    }
}
