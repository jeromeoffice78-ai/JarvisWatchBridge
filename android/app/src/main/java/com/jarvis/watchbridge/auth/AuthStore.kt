package com.jarvis.watchbridge.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AuthStore {
    data class EnrollmentResult(
        val deviceId: String,
        val expiresAt: Long
    )

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "jarvis_chairman_session_v1"
    private const val PREFS = "jarvis_chairman_auth"
    private const val PREF_CIPHERTEXT = "session_ciphertext"
    private const val PREF_IV = "session_iv"
    private const val PREF_EXPIRES_AT = "session_expires_at"
    private const val PREF_DEVICE_ID = "session_device_id"

    @Volatile private var appContext: Context? = null
    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        getOrCreateKey()
    }

    fun isEnrolled(): Boolean = currentToken() != null

    fun requireToken(): String = currentToken()
        ?: throw IllegalStateException("Chairman device is not paired. Open Chairman Access and enter the pairing code.")

    fun deviceId(): String {
        val context = context()
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "android-${androidId ?: Build.MODEL.replace(' ', '-')}"
    }

    fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android device" }
        .take(256)

    fun expiresAt(): Long = context().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(PREF_EXPIRES_AT, 0L)

    fun currentToken(): String? {
        val context = context()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expiresAt = prefs.getLong(PREF_EXPIRES_AT, 0L)
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis() / 1000L) {
            clear()
            return null
        }
        val ciphertext = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(PREF_IV, null) ?: return null
        return runCatching { decrypt(ciphertext, iv) }
            .getOrElse {
                clear()
                null
            }
            ?.takeIf { it.startsWith("jv1.") }
    }

    suspend fun enroll(pairingCode: String): EnrollmentResult = withContext(Dispatchers.IO) {
        val clean = pairingCode.trim()
        require(clean.length >= 8) { "Enter the Chairman pairing code." }
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Secure JARVIS API URL required" }
        val id = deviceId()
        val body = JSONObject()
            .put("pairingCode", clean)
            .put("deviceId", id)
            .put("deviceName", deviceName())
            .toString()
            .toRequestBody(json)
        val request = Request.Builder()
            .url("$base/auth/enroll")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Chairman enrollment ${response.code}: ${safeError(text)}")
            val root = JSONObject(text)
            val token = root.optString("token")
            val expires = root.optLong("expiresAt", 0L)
            require(token.startsWith("jv1.")) { "Enrollment returned an invalid device session." }
            require(expires > System.currentTimeMillis() / 1000L) { "Enrollment returned an expired device session." }
            store(token, id, expires)
            EnrollmentResult(id, expires)
        }
    }

    suspend fun verifySession(): Boolean = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext false
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return@withContext false
        val request = Request.Builder()
            .url("$base/auth/session")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    fun clear() {
        context().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun store(token: String, deviceId: String, expiresAt: Long) {
        val (ciphertext, iv) = encrypt(token)
        context().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_CIPHERTEXT, ciphertext)
            .putString(PREF_IV, iv)
            .putLong(PREF_EXPIRES_AT, expiresAt)
            .putString(PREF_DEVICE_ID, deviceId)
            .apply()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val clear = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return clear.toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun context(): Context = appContext
        ?: throw IllegalStateException("JARVIS authentication is not initialized")

    private fun safeError(text: String): String {
        return runCatching {
            val root = JSONObject(text)
            when (val detail = root.opt("detail")) {
                is String -> detail
                null -> "Enrollment failed"
                else -> detail.toString()
            }
        }.getOrDefault("Enrollment failed").take(500)
    }
}
