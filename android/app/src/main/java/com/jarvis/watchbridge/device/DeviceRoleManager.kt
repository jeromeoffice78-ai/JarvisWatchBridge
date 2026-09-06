package com.jarvis.watchbridge.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DeviceRoleState(
    val role: String,
    val primaryDeviceId: String?
)

class DeviceRoleManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        "android-${androidId ?: Build.MODEL.replace(' ', '-')}"
    }
    val deviceName: String get() = if (isGalaxyA16()) "Jerome's Galaxy A16" else "Jerome's ${Build.MODEL}"
    val deviceModel: String get() = Build.MODEL ?: "Android"
    val preferredRole: String get() = if (isGalaxyA16()) "primary" else "companion"

    private fun isGalaxyA16(): Boolean {
        val model = (Build.MODEL ?: "").uppercase()
        return model.startsWith("SM-A166") || model.contains("A16")
    }

    suspend fun register(connected: Boolean, watchBleAddress: String?): DeviceRoleState = withContext(Dispatchers.IO) {
        post("device/register", JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceModel", deviceModel)
            put("preferredRole", preferredRole)
            put("connected", connected)
            if (watchBleAddress != null) put("watchBleAddress", watchBleAddress)
        }).let {
            DeviceRoleState(
                it.optString("role", "companion"),
                it.optString("primaryDeviceId").ifBlank { null }
            )
        }
    }

    suspend fun heartbeat(connected: Boolean, watchBleAddress: String?): DeviceRoleState = withContext(Dispatchers.IO) {
        val result = post("device/heartbeat", JSONObject().apply {
            put("deviceId", deviceId)
            put("connected", connected)
            if (watchBleAddress != null) put("watchBleAddress", watchBleAddress)
        })
        DeviceRoleState(result.optString("role").ifBlank { "companion" }, null)
    }

    suspend fun takeOver(watchBleAddress: String?): DeviceRoleState = withContext(Dispatchers.IO) {
        val result = post("device/takeover", JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("deviceModel", deviceModel)
            if (watchBleAddress != null) put("watchBleAddress", watchBleAddress)
        })
        DeviceRoleState("primary", result.optString("primaryDeviceId").ifBlank { deviceId })
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        require(base.startsWith("https://")) { "Secure JARVIS bridge URL required" }
        require(token.isNotBlank()) { "Owner access is not configured in this build" }
        val request = Request.Builder()
            .url("$base/$path")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Device sync ${response.code}: $text")
            return JSONObject(text)
        }
    }
}
