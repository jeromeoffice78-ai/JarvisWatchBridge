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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun send(message: String, healthContext: String?): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("message", message)
            if (healthContext != null) put("health_context", healthContext)
        }.toString().toRequestBody(json)

        val builder = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/chat")
            .addHeader("Accept", "application/json")
            .post(body)

        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        if (token.isNotBlank()) {
            builder.addHeader("x-jarvis-admin-token", token)
        }

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("API ${response.code}: $text")
            JSONObject(text).getString("reply")
        }
    }
}
