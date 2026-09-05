package com.jarvis.watchbridge.notifications

import com.jarvis.watchbridge.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject


data class PhoneMessage(
    val id: String,
    val callerPhone: String?,
    val summary: String,
    val transcript: String?,
    val status: String?,
    val createdAt: String?
)

class PhoneMessageRepository {
    private val client = OkHttpClient()

    fun latest(): PhoneMessage? {
        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        if (token.isBlank()) return null

        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val request = Request.Builder()
            .url("$base/phone/messages")
            .addHeader("x-jarvis-admin-token", token)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val messages = root.optJSONArray("messages") ?: return null
            if (messages.length() == 0) return null
            val item = messages.getJSONObject(0)
            return PhoneMessage(
                id = item.optString("id"),
                callerPhone = item.optString("callerPhone").ifBlank { null },
                summary = item.optString("summary").ifBlank { "Call completed." },
                transcript = item.optString("transcript").ifBlank { null },
                status = item.optString("status").ifBlank { null },
                createdAt = item.optString("createdAt").ifBlank { null }
            )
        }
    }
}
