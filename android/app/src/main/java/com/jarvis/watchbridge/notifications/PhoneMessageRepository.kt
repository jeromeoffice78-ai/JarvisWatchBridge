package com.jarvis.watchbridge.notifications

import com.jarvis.watchbridge.auth.AuthStore

import com.jarvis.watchbridge.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit


data class PhoneMessage(
    val id: String,
    val callerPhone: String?,
    val summary: String,
    val transcript: String?,
    val status: String?,
    val createdAt: String?
)

class PhoneMessageRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun latest(): PhoneMessage? {
        val token = AuthStore.requireToken()
        if (token.isBlank()) return null

        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return null

        val request = Request.Builder()
            .url("$base/phone/messages")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val messages = root.optJSONArray("messages") ?: return null
            if (messages.length() == 0) return null

            var latest: JSONObject? = null
            var latestCreatedAt = ""
            for (i in 0 until messages.length()) {
                val candidate = messages.optJSONObject(i) ?: continue
                val id = candidate.optString("id").trim()
                if (id.isBlank()) continue
                val createdAt = candidate.optString("createdAt")
                if (latest == null || createdAt > latestCreatedAt) {
                    latest = candidate
                    latestCreatedAt = createdAt
                }
            }

            val item = latest ?: return null
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
