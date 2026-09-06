package com.jarvis.watchbridge.control

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JarvisRemoteCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = "application/json; charset=utf-8".toMediaType()
    private val processed = LinkedHashSet<String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(
            2405,
            NotificationCompat.Builder(this, "jarvis_remote_control")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("JARVIS device control active")
                .setContentText("Remote commands are visible and permission-controlled.")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        scope.launch { pollLoop() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(2500)
        }
    }

    private fun pollOnce() {
        val deviceId = DeviceIdentity.id(this)
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        if (!base.startsWith("https://") || token.isBlank()) return

        val req = Request.Builder()
            .url("$base/device/commands?deviceId=${Uri.encode(deviceId)}")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .get()
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return
            val payload = JSONObject(response.body?.string().orEmpty())
            val commands = payload.optJSONArray("commands") ?: return
            for (i in 0 until commands.length()) {
                val command = commands.optJSONObject(i) ?: continue
                val id = command.optString("id")
                if (id.isBlank() || processed.contains(id)) continue
                if (command.optString("status") != "approved") continue
                val result = execute(command)
                processed += id
                while (processed.size > 500) processed.remove(processed.first())
                postResult(id, deviceId, result)
            }
        }
    }

    private fun execute(command: JSONObject): ActionResult {
        val action = command.optString("action")
        val payload = command.optJSONObject("payload") ?: JSONObject()
        return when (action) {
            "open_app" -> openApp(payload.optString("packageName"))
            "open_url" -> openUrl(payload.optString("url"))
            "home" -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Home opened.")
            "back" -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Went back.")
            "recents" -> global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents opened.")
            "volume_set" -> setVolume(payload.optInt("percent", 50))
            "flashlight_set" -> setFlashlight(payload.optBoolean("enabled", false))
            "media_play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media_next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "media_previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "open_navigation" -> openNavigation(payload.optString("query"))
            "open_settings" -> openSettings(payload.optString("screen"))
            "ui_tap_text" -> JarvisAccessibilityService.tapText(payload.optString("text"))
            "ui_type_text" -> JarvisAccessibilityService.typeText(payload.optString("text"))
            "ui_scroll" -> if (payload.optString("direction").equals("backward", true)) {
                JarvisAccessibilityService.scrollBackward()
            } else {
                JarvisAccessibilityService.scrollForward()
            }
            "compose_sms" -> composeSms(payload)
            "compose_email" -> composeEmail(payload)
            "dial_number" -> dial(payload.optString("number"))
            "set_alarm" -> setAlarm(payload)
            "set_timer" -> setTimer(payload)
            else -> ActionResult(false, "Unsupported command: $action")
        }
    }

    private fun global(action: Int, success: String): ActionResult {
        val ok = JarvisAccessibilityService.performGlobal(action)
        return ActionResult(ok, if (ok) success else "Android rejected the global action.")
    }

    private fun openApp(packageName: String): ActionResult {
        if (packageName.isBlank()) return ActionResult(false, "Package name required.")
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "App is not installed or has no launch activity.")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "App opened.")
    }

    private fun openUrl(raw: String): ActionResult {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return ActionResult(false, "Invalid URL.")
        if (uri.scheme !in setOf("https", "http")) return ActionResult(false, "Only http/https URLs are allowed.")
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "URL opened.")
    }

    private fun setVolume(percent: Int): ActionResult {
        val audio = getSystemService(AudioManager::class.java)
        val safe = percent.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = ((safe / 100.0) * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        return ActionResult(true, "Media volume set to $safe%.")
    }

    private fun setFlashlight(enabled: Boolean): ActionResult {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, "Camera permission is required for flashlight control.")
        }
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        } ?: return ActionResult(false, "No controllable flashlight found.")
        return runCatching {
            manager.setTorchMode(cameraId, enabled)
            ActionResult(true, if (enabled) "Flashlight on." else "Flashlight off.")
        }.getOrElse { ActionResult(false, it.message ?: "Flashlight command failed.") }
    }

    private fun mediaKey(code: Int): ActionResult {
        val audio = getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ActionResult(true, "Media command sent.")
    }

    private fun openNavigation(query: String): ActionResult {
        if (query.isBlank()) return ActionResult(false, "Destination required.")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "Navigation opened.")
    }

    private fun openSettings(screen: String): ActionResult {
        val action = when (screen.lowercase()) {
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "Settings opened.")
    }

    private fun composeSms(payload: JSONObject): ActionResult {
        val number = payload.optString("number")
        val body = payload.optString("body")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return ActionResult(true, "SMS draft opened.")
    }

    private fun composeEmail(payload: JSONObject): ActionResult {
        val to = payload.optString("to")
        val subject = payload.optString("subject")
        val body = payload.optString("body")
        val uri = Uri.parse("mailto:${Uri.encode(to)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "Email draft opened.")
    }

    private fun dial(number: String): ActionResult {
        if (number.isBlank()) return ActionResult(false, "Phone number required.")
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult(true, "Dialer opened.")
    }

    private fun setAlarm(payload: JSONObject): ActionResult {
        val hour = payload.optInt("hour", -1)
        val minute = payload.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return ActionResult(false, "Valid hour and minute required.")
        startActivity(
            Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return ActionResult(true, "Alarm setup opened.")
    }

    private fun setTimer(payload: JSONObject): ActionResult {
        val seconds = payload.optInt("seconds", 0)
        if (seconds <= 0) return ActionResult(false, "Positive timer duration required.")
        startActivity(
            Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return ActionResult(true, "Timer setup opened.")
    }

    private fun postResult(commandId: String, deviceId: String, result: ActionResult) {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        val token = BuildConfig.JARVIS_SETUP_TOKEN.trim()
        val body = JSONObject().apply {
            put("commandId", commandId)
            put("deviceId", deviceId)
            put("status", if (result.success) "succeeded" else "failed")
            put("result", JSONObject().put("message", result.message))
        }
        val req = Request.Builder()
            .url("$base/device/result")
            .addHeader("x-jarvis-admin-token", token)
            .post(body.toString().toRequestBody(json))
            .build()
        runCatching { client.newCall(req).execute().close() }
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("jarvis_remote_control", "JARVIS Device Control", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisRemoteCommandService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisRemoteCommandService::class.java))
        }
    }
}

object DeviceIdentity {
    fun id(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "android-${androidId ?: android.os.Build.MODEL.replace(' ', '-')}"
    }
}
