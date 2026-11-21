package com.aisling.somawatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GitHubReleaseInfo(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val wearApkUrl: String?,
    val mobileApkUrl: String?
)

object SomaMobileState {
    private val _connectedWatchName = MutableStateFlow("Поиск Galaxy Watch 8...")
    val connectedWatchName: StateFlow<String> = _connectedWatchName

    private val _lastWatchMessage = MutableStateFlow("Пока нет сообщений")
    val lastWatchMessage: StateFlow<String> = _lastWatchMessage

    private val _julesStatus = MutableStateFlow("Готов отправлять задачи в GitHub Issues")
    val julesStatus: StateFlow<String> = _julesStatus

    private val _otaStatus = MutableStateFlow("OTA готов (${BuildConfig.GITHUB_REPO})")
    val otaStatus: StateFlow<String> = _otaStatus

    private val _otaProgress = MutableStateFlow<Float?>(null)
    val otaProgress: StateFlow<Float?> = _otaProgress

    private val _latestRelease = MutableStateFlow<GitHubReleaseInfo?>(null)
    val latestRelease: StateFlow<GitHubReleaseInfo?> = _latestRelease

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    fun setConnectedWatch(name: String) {
        _connectedWatchName.value = name
    }

    fun recordWatchMessage(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastWatchMessage.value = "[$time] $msg"
    }

    fun setJulesStatus(status: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _julesStatus.value = "[$time] $status"
    }

    fun setOtaStatus(status: String) {
        _otaStatus.value = status
    }

    fun setOtaProgress(progress: Float?) {
        _otaProgress.value = progress
    }

    fun setLatestRelease(info: GitHubReleaseInfo?) {
        _latestRelease.value = info
    }

    fun setBusy(busy: Boolean) {
        _isBusy.value = busy
    }
}
