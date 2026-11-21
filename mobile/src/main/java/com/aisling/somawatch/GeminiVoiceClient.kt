package com.aisling.somawatch

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GeminiVoiceClient {
    private const val TAG = "SomaGeminiVoice"
    private const val PREFS_NAME = "soma_watch_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"
    private const val MODEL = "gemini-2.5-flash"

    fun getSavedApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GEMINI_API, "")?.trim().orEmpty()
    }

    fun saveApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_API, apiKey.trim()).apply()
    }

    suspend fun transcribeAudio(context: Context, audioFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey = getSavedApiKey(context)
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Задайте Gemini API Key в Soma Watch на телефоне")
                )
            }

            try {
                val audioBytes = audioFile.readBytes()
                val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

                val promptText = """
                    Расшифруй эту голосовую заметку пользователя на русском языке максимально точно.
                    Пользователь диктует задачу на доработку приложения для умных часов Soma Watch (Wear OS / Android).
                    Аккуратно сохраняй английские технические термины и аббревиатуры (HRV, BPM, HRR, Zone 2, Wear OS, OTA, Jules, Gemini, UI, GitHub, децибелы и т.д.).
                    Убери слова-паразиты, но полностью сохрани смысл и детали.
                    Верни ТОЛЬКО чистый текст задачи, без кавычек, заголовков и лишних комментариев.
                """.trimIndent()

                val inlineData = JSONObject().apply {
                    put("mime_type", "audio/mp4")
                    put("data", base64Audio)
                }
                val parts = JSONArray()
                    .put(JSONObject().put("text", promptText))
                    .put(JSONObject().put("inline_data", inlineData))

                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                }

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 20000
                    readTimeout = 30000
                }

                conn.outputStream.use { out ->
                    out.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    Log.e(TAG, "Gemini HTTP $code: $body")
                    return@withContext Result.failure(IllegalStateException("Gemini HTTP $code"))
                }

                val root = JSONObject(body)
                val candidates = root.optJSONArray("candidates")
                val text = candidates
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                    .orEmpty()

                if (text.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Gemini вернул пустой ответ"))
                }

                Log.i(TAG, "Gemini transcribed: $text")
                Result.success(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed transcribing audio with Gemini", e)
                Result.failure(e)
            }
        }
}
