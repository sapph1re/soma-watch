package com.aisling.somawatch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHubJulesClient {
    private const val TAG = "SomaJulesClient"
    private const val PREFS_NAME = "soma_watch_prefs"
    private const val KEY_GITHUB_TOKEN = "github_pat"

    fun getSavedToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GITHUB_TOKEN, "")?.trim().orEmpty()
    }

    fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
    }

    suspend fun createJulesIssue(
        context: Context,
        promptText: String,
        source: String,
        replyToNodeId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanPrompt = promptText.trim()
        if (cleanPrompt.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Пустой промпт"))
        }

        val token = getSavedToken(context)
        if (token.isEmpty()) {
            val msg = "Задайте GitHub Token в Soma Companion на телефоне"
            SomaMobileState.setJulesStatus(msg)
            notifyWatch(context, replyToNodeId, "Нужен GitHub Token на телефоне")
            return@withContext Result.failure(IllegalStateException(msg))
        }

        try {
            SomaMobileState.setBusy(true)
            SomaMobileState.setJulesStatus("Создаём Issue с лейблом jules...")

            val shortTitle = if (cleanPrompt.length > 68) {
                cleanPrompt.take(65) + "..."
            } else {
                cleanPrompt
            }

            val issueBody = buildString {
                appendLine("### Задача от пользователя ($source)")
                appendLine()
                appendLine(cleanPrompt)
                appendLine()
                appendLine("---")
                appendLine("- **Источник:** `$source` (Soma Watch v${BuildConfig.VERSION_NAME})")
                appendLine("- **Инструкция для агента:** Прочитай `GEMINI.md`, реализуй фичу аккуратно и убедись, что `./gradlew :wear:assembleDebug :mobile:assembleDebug` проходит без ошибок. Не удаляй OTA-модуль и не меняй `applicationId`.")
            }

            val payload = JSONObject().apply {
                put("title", "[$source] $shortTitle")
                put("body", issueBody)
                put("labels", JSONArray().put("soma-agent"))
            }

            val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/issues")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 15000
                readTimeout = 15000
            }

            conn.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val responseStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                Log.e(TAG, "GitHub API error HTTP $code: $body")
                val errMsg = "GitHub HTTP $code"
                SomaMobileState.setJulesStatus("Ошибка создания Issue: $errMsg")
                notifyWatch(context, replyToNodeId, "Ошибка: $errMsg")
                return@withContext Result.failure(IllegalStateException(errMsg))
            }

            val json = JSONObject(body)
            val issueNumber = json.optInt("number", 0)
            val successMsg = "Issue #$issueNumber создан → Jules!"
            SomaMobileState.setJulesStatus("$successMsg («$shortTitle»)")
            notifyWatch(context, replyToNodeId, successMsg)
            Result.success(successMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Jules issue", e)
            val errMsg = e.localizedMessage ?: "Сетевая ошибка"
            SomaMobileState.setJulesStatus("Ошибка: $errMsg")
            notifyWatch(context, replyToNodeId, "Ошибка: $errMsg")
            Result.failure(e)
        } finally {
            SomaMobileState.setBusy(false)
        }
    }

    private suspend fun notifyWatch(context: Context, nodeId: String?, message: String) {
        runCatching {
            val targetIds = if (nodeId != null) {
                listOf(nodeId)
            } else {
                Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
            }
            targetIds.forEach { id ->
                Wearable.getMessageClient(context)
                    .sendMessage(id, "/soma/jules_result", message.toByteArray(Charsets.UTF_8))
                    .await()
            }
        }
    }
}
