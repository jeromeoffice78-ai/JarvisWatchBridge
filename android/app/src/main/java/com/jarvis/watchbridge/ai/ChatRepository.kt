package com.jarvis.watchbridge.ai

import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun send(message: String, healthContext: String?): String = withContext(Dispatchers.IO) {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        require(base.startsWith("https://")) { "Secure JARVIS bridge URL required" }
        require(token.isNotBlank()) { "Chairman access token is not configured" }
        require(message.isNotBlank()) { "Message cannot be blank" }

        val body = JSONObject().apply {
            put("message", message.take(4_000))
            if (!healthContext.isNullOrBlank()) put("health_context", healthContext.take(8_000))
        }.toString().toRequestBody(json)

        val request = Request.Builder()
            .url("$base/chat")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("JARVIS API ${response.code}: ${text.take(600)}")
            val root = JSONObject(text)
            root.optString("reply").takeIf { it.isNotBlank() }
                ?: error("JARVIS API returned no reply")
        }
    }
}
