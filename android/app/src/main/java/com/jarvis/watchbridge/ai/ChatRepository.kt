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
        require(token.isNotBlank()) { "Owner access is not configured in this build" }

        val body = JSONObject().apply {
            put("message", message)
            if (healthContext != null) put("health_context", healthContext)
        }.toString().toRequestBody(json)

        val request = Request.Builder()
            .url("$base/chat")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("API ${response.code}: $text")
            JSONObject(text).getString("reply")
        }
    }
}
