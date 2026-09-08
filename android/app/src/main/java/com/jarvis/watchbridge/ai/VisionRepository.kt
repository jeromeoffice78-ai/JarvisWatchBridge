package com.jarvis.watchbridge.ai

import android.util.Base64
import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VisionRepository {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()
    suspend fun describe(image: ByteArray): String = withContext(Dispatchers.IO) {
        require(image.isNotEmpty()) { "Camera returned an empty image." }
        require(image.size <= 4_000_000) { "Camera image is too large." }
        val body = JSONObject().apply {
            put("image_base64", Base64.encodeToString(image, Base64.NO_WRAP))
            put("prompt", "Describe what you can see for Chairman Jerome. Be respectful, concise, and mention only visible details. Do not identify a person by name from appearance alone.")
        }.toString().toRequestBody(json)
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/vision").addHeader("x-jarvis-admin-token", BuildConfig.JARVIS_SETUP_TOKEN.trim()).addHeader("Accept", "application/json").post(body).build()
        client.newCall(request).execute().use { result ->
            val text = result.body?.string().orEmpty()
            if (!result.isSuccessful) error("Vision service " + result.code)
            JSONObject(text).optString("reply").ifBlank { "I received the camera image but no description was returned." }
        }
    }
}
