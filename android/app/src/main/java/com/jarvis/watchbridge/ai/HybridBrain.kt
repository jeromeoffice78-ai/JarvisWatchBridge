package com.jarvis.watchbridge.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.jarvis.watchbridge.memory.LocalKnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * JARVIS hybrid brain:
 * 1) online: cloud reasoning + web-capable backend + local-memory context;
 * 2) successful answers are retained in the local knowledge vault;
 * 3) offline: on-device LLM if installed, otherwise deterministic local-memory retrieval.
 */
class HybridBrain(context: Context) {
    data class BrainReply(
        val text: String,
        val mode: String,
        val memoryCount: Int,
        val offlineModelReady: Boolean
    )

    private val appContext = context.applicationContext
    private val cloud = ChatRepository()
    val memory = LocalKnowledgeStore(appContext)
    val offline = OfflineBrain(appContext, memory)

    suspend fun answer(message: String, deviceContext: String? = null): BrainReply {
        val clean = message.trim()
        require(clean.isNotBlank()) { "Message cannot be blank" }

        handleLocalCommand(clean)?.let { return it }

        val remembered = withContext(Dispatchers.IO) { memory.contextFor(clean) }
        val combinedContext = buildString {
            if (!deviceContext.isNullOrBlank()) {
                append(deviceContext.take(3000)).append("\n\n")
            }
            if (remembered.isNotBlank()) append(remembered.take(4500))
        }.take(7900)

        if (isOnline()) {
            val online = runCatching { cloud.send(clean, combinedContext) }.getOrNull()
            if (!online.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    memory.remember(
                        topic = clean.take(220),
                        content = online,
                        sourceUrl = null
                    )
                }
                val status = offline.status()
                return BrainReply(
                    text = online,
                    mode = "ONLINE + LOCAL MEMORY",
                    memoryCount = memory.count(),
                    offlineModelReady = status.installed && status.compatible
                )
            }
        }

        val generated = offline.generate(clean, deviceContext.orEmpty())
        val status = offline.status()
        return BrainReply(
            text = generated ?: offline.memoryOnlyAnswer(clean),
            mode = if (generated != null) "OFFLINE LOCAL AI" else "OFFLINE MEMORY",
            memoryCount = memory.count(),
            offlineModelReady = status.installed && status.compatible
        )
    }

    suspend fun installOfflineModel(uri: Uri): OfflineBrain.Status = offline.installModel(uri)

    fun statusLine(): String {
        val status = offline.status()
        val network = if (isOnline()) "online" else "offline"
        return "$network • ${memory.count()} memories • ${status.message}"
    }

    fun shutdown() {
        offline.shutdown()
        memory.close()
    }

    private suspend fun handleLocalCommand(message: String): BrainReply? {
        val normalized = message.lowercase(Locale.US).trim()
        val status = offline.status()

        if (normalized in setOf("memory status", "jarvis memory status", "offline status")) {
            return BrainReply(statusLine(), "LOCAL SYSTEM", memory.count(), status.installed && status.compatible)
        }

        if (normalized in setOf("forget offline memory", "clear offline memory", "clear jarvis memory")) {
            withContext(Dispatchers.IO) { memory.clearAll() }
            return BrainReply(
                "Local JARVIS knowledge memory has been cleared from this device.",
                "LOCAL SYSTEM",
                0,
                status.installed && status.compatible
            )
        }

        val rememberPrefixes = listOf("remember that ", "remember this: ", "learn this: ")
        val prefix = rememberPrefixes.firstOrNull { normalized.startsWith(it) }
        if (prefix != null) {
            val content = message.substring(prefix.length).trim()
            val stored = withContext(Dispatchers.IO) {
                memory.remember("User-taught memory", content)
            }
            val text = if (stored) {
                "Remembered locally. I can recall that even without internet."
            } else {
                "I didn't store that automatically because it looked empty, duplicate, or sensitive."
            }
            return BrainReply(text, "LOCAL MEMORY", memory.count(), status.installed && status.compatible)
        }

        return null
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
