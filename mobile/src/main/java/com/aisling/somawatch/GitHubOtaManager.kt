package com.aisling.somawatch

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GitHubOtaManager {
    private const val TAG = "SomaOtaManager"

    suspend fun checkLatestRelease(repo: String = BuildConfig.GITHUB_REPO): GitHubReleaseInfo? =
        withContext(Dispatchers.IO) {
            try {
                SomaMobileState.setBusy(true)
                SomaMobileState.setOtaStatus("Проверяем GitHub Releases ($repo)...")
                val url = URL("https://api.github.com/repos/$repo/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                if (conn.responseCode == 404) {
                    SomaMobileState.setOtaStatus("Релизов в $repo пока нет (404)")
                    return@withContext null
                }
                if (conn.responseCode !in 200..299) {
                    SomaMobileState.setOtaStatus("Ошибка GitHub API: HTTP ${conn.responseCode}")
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "unknown")
                val name = json.optString("name", tagName)
                val publishedAt = json.optString("published_at", "")
                val assets = json.optJSONArray("assets")

                var wearUrl: String? = null
                var mobileUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (assetName.contains("wear", ignoreCase = true) && assetName.endsWith(".apk")) {
                            wearUrl = downloadUrl
                        } else if (assetName.contains("mobile", ignoreCase = true) && assetName.endsWith(".apk")) {
                            mobileUrl = downloadUrl
                        }
                    }
                }

                val info = GitHubReleaseInfo(
                    tagName = tagName,
                    name = name,
                    publishedAt = publishedAt,
                    wearApkUrl = wearUrl,
                    mobileApkUrl = mobileUrl
                )
                SomaMobileState.setLatestRelease(info)
                SomaMobileState.setOtaStatus("Найден релиз: $name")
                info
            } catch (e: Exception) {
                Log.e(TAG, "Error checking GitHub releases", e)
                SomaMobileState.setOtaStatus("Ошибка проверки OTA: ${e.localizedMessage}")
                null
            } finally {
                SomaMobileState.setBusy(false)
            }
        }

    suspend fun pushWearOtaToWatch(context: Context, wearApkUrl: String) = withContext(Dispatchers.IO) {
        try {
            SomaMobileState.setBusy(true)
            SomaMobileState.setOtaStatus("Скачиваем wear-debug.apk из GitHub...")
            notifyWatchStatus(context, "Телефон скачивает OTA...")

            val wearApkFile = File(context.cacheDir, "soma-wear-ota.apk")
            if (wearApkFile.exists()) wearApkFile.delete()
            downloadFile(wearApkUrl, wearApkFile)

            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) {
                SomaMobileState.setOtaStatus("Часы Galaxy Watch 8 не найдены по Bluetooth!")
                return@withContext
            }

            val targetNode = nodes.first()
            SomaMobileState.setOtaStatus("Отправка APK (${wearApkFile.length() / 1024} KB) на ${targetNode.displayName}...")

            val channelClient = Wearable.getChannelClient(context)
            val channel = channelClient.openChannel(targetNode.id, "/soma/ota_apk").await()
            val outputStream = channelClient.getOutputStream(channel).await()

            wearApkFile.inputStream().use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            SomaMobileState.setOtaStatus("APK передан на ${targetNode.displayName}! Подтвердите установку на часах.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push Wear OTA", e)
            SomaMobileState.setOtaStatus("Ошибка отправки на часы: ${e.localizedMessage}")
        } finally {
            SomaMobileState.setBusy(false)
        }
    }

    suspend fun downloadAndInstallMobileOta(context: Context, mobileApkUrl: String) = withContext(Dispatchers.IO) {
        try {
            SomaMobileState.setBusy(true)
            SomaMobileState.setOtaStatus("Скачиваем mobile-debug.apk из GitHub...")
            val mobileApkFile = File(context.cacheDir, "soma-mobile-ota.apk")
            if (mobileApkFile.exists()) mobileApkFile.delete()
            downloadFile(mobileApkUrl, mobileApkFile)

            SomaMobileState.setOtaStatus("Запуск установщика на телефоне...")
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                mobileApkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install Mobile OTA", e)
            SomaMobileState.setOtaStatus("Ошибка обновления телефона: ${e.localizedMessage}")
        } finally {
            SomaMobileState.setBusy(false)
        }
    }

    private fun downloadFile(urlStr: String, destFile: File) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
            }
            val code = conn.responseCode
            if (code in 300..399) {
                currentUrl = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without Location")
                redirects++
                continue
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code when downloading APK")
            }
            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        throw IllegalStateException("Too many redirects")
    }

    private suspend fun notifyWatchStatus(context: Context, status: String) {
        runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/soma/ota_status", status.toByteArray(Charsets.UTF_8))
                    .await()
            }
        }
    }
}
