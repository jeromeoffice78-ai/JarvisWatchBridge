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
            .addHeader("User-Agent", "JARVIS-Chairman/${BuildConfig.VERSION_NAME}")
            .post(body)

        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        if (token.isNotBlank()) {
            builder.addHeader("x-jarvis-admin-token", token)
        }

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val contentType = response.header("content-type").orEmpty().lowercase()
                val message = when {
                    response.code == 403 && (contentType.contains("text/html") || text.contains("CloudFront", ignoreCase = true)) ->
                        "JARVIS reached the wrong web gateway. Install the latest Chairman Render build."
                    response.code == 503 && text.contains("OPENAI_API_KEY", ignoreCase = true) ->
                        "JARVIS backend is online, but the OpenAI API key still needs to be connected."
                    response.code == 401 ->
                        "JARVIS Chairman authorization failed."
                    text.trim().startsWith("{") -> runCatching {
                        val root = JSONObject(text)
                        root.optString("detail").ifBlank { root.optString("error") }
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: "JARVIS API error ${response.code}."
                    else -> "JARVIS API error ${response.code}."
                }
                error(message)
            }

            val root = runCatching { JSONObject(text) }.getOrElse {
                error("JARVIS backend returned an invalid response.")
            }
            root.optString("reply").takeIf { it.isNotBlank() }
                ?: error("JARVIS backend returned no reply.")
        }
    }

    suspend fun runBoard(objective: String = "Review current priorities and recommend the next best actions."): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("objective", objective).toString().toRequestBody(json)
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/board/run")
            .addHeader("Accept", "application/json").addHeader("User-Agent", "JARVIS-Chairman/${BuildConfig.VERSION_NAME}").post(body)
        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        if (token.isNotBlank()) builder.addHeader("x-jarvis-admin-token", token)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Autonomous board API error ${response.code}.")
            JSONObject(text).optString("briefing").takeIf { it.isNotBlank() } ?: error("The autonomous board returned no briefing.")
        }
    }
}
