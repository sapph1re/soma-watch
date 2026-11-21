package com.aisling.somawatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SomaWearState {
    private val _statusMessage = MutableStateFlow("Готов к работе")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _lastSyncInfo = MutableStateFlow("Нажми 🎙️ чтобы надиктовать фичу")
    val lastSyncInfo: StateFlow<String> = _lastSyncInfo

    private val _otaProgress = MutableStateFlow<String?>(null)
    val otaProgress: StateFlow<String?> = _otaProgress

    private val _isOtaBusy = MutableStateFlow(false)
    val isOtaBusy: StateFlow<Boolean> = _isOtaBusy

    private val _pendingVoicePrompt = MutableStateFlow<String?>(null)
    val pendingVoicePrompt: StateFlow<String?> = _pendingVoicePrompt

    fun setOtaBusy(busy: Boolean) {
        _isOtaBusy.value = busy
    }

    fun updateStatus(msg: String) {
        _statusMessage.value = msg
    }

    fun recordPing(from: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastSyncInfo.value = "$from ($time)"
        _statusMessage.value = "Пинг получен ($time)"
    }

    fun recordJulesResult(result: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastSyncInfo.value = "Jules [$time]: $result"
        _statusMessage.value = result
    }

    fun updateOtaProgress(msg: String?) {
        _otaProgress.value = msg
    }

    fun setPendingVoicePrompt(text: String?) {
        _pendingVoicePrompt.value = text
    }
}
