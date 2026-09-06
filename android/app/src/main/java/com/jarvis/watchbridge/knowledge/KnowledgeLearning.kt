package com.jarvis.watchbridge.knowledge

import com.jarvis.watchbridge.auth.AuthStore

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class KnowledgeRepository {
    data class Stats(val sources: Int, val chunks: Int, val queued: Int)

    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        val root = get("/knowledge/stats")
        Stats(
            sources = root.optInt("sources", 0),
            chunks = root.optInt("chunks", 0),
            queued = root.optInt("crawl_queue", 0)
        )
    }

    suspend fun crawlBatch(limit: Int = 3): String = withContext(Dispatchers.IO) {
        val root = post("/knowledge/crawl-batch?limit=${limit.coerceIn(1, 10)}", JSONObject())
        val claimed = root.optInt("claimed", 0)
        val results = root.optJSONArray("results")
        val learned = if (results == null) 0 else (0 until results.length()).count { index ->
            results.optJSONObject(index)?.optString("status") == "learned"
        }
        "Processed $claimed queued sources • learned $learned"
    }

    suspend fun learnUrl(url: String): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(clean.startsWith("https://") || clean.startsWith("http://")) { "Enter a full http/https URL" }
        val root = post(
            "/knowledge/learn",
            JSONObject().put("url", clean).put("discoverLinks", true)
        )
        when (root.optString("status")) {
            "learned" -> "Learned ${root.optInt("chunks", 0)} chunks • queued ${root.optInt("discovered_links_queued", 0)} related links"
            "blocked" -> "Not crawled: ${root.optString("reason", "robots policy")}" 
            else -> root.toString()
        }
    }

    private fun get(path: String): JSONObject {
        val request = Request.Builder()
            .url(url(path))
            .addHeader("x-jarvis-admin-token", token())
            .addHeader("Accept", "application/json")
            .get()
            .build()
        return execute(request)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url(path))
            .addHeader("x-jarvis-admin-token", token())
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("Knowledge API ${response.code}: ${text.take(800)}")
        JSONObject(text)
    }

    private fun url(path: String): String {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Secure JARVIS API URL required" }
        return "$base$path"
    }

    private fun token(): String = AuthStore.requireToken().also {
        require(it.isNotBlank()) { "Chairman access token is not configured" }
    }
}

class KnowledgeLearningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            KnowledgeRepository().crawlBatch(3)
            Result.success()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object KnowledgeLearningScheduler {
    private const val UNIQUE_PERIODIC = "jarvis-continuous-knowledge-learning"
    private const val PREFS = "jarvis_knowledge_learning"
    private const val KEY_ENABLED = "enabled"

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<KnowledgeLearningWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<KnowledgeLearningWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
}
