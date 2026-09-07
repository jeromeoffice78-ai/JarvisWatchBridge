package com.jarvis.watchbridge.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.jarvis.watchbridge.memory.LocalKnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional, fully on-device LLM runtime.
 *
 * The model is deliberately not bundled into the APK because production-quality LLM model files
 * are large and hardware-specific. The Chairman can import a compatible MediaPipe .task model;
 * JARVIS copies it into app-private storage and can then answer without any network connection.
 * If no compatible model is installed or the device cannot initialize it, HybridBrain falls back
 * to the durable local knowledge vault rather than failing the conversation.
 */
class OfflineBrain(
    private val context: Context,
    private val memory: LocalKnowledgeStore
) {
    data class Status(
        val installed: Boolean,
        val compatible: Boolean,
        val modelBytes: Long,
        val message: String
    )

    private val modelFile = File(context.filesDir, MODEL_FILE_NAME)
    private val engineLock = Any()
    @Volatile private var engine: LlmInference? = null

    fun status(): Status {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val compatible = info.totalMem >= MIN_TOTAL_RAM_BYTES
        val bytes = if (modelFile.exists()) modelFile.length() else 0L
        val installed = bytes >= MIN_MODEL_BYTES
        val message = when {
            !compatible -> "Device does not have enough RAM for the optional local LLM; offline memory remains available."
            !installed -> "Offline knowledge is ready. Import a compatible local .task model to enable offline generative AI."
            else -> "Offline local AI model ready."
        }
        return Status(installed, compatible, bytes, message)
    }

    suspend fun installModel(uri: Uri): Status = withContext(Dispatchers.IO) {
        shutdown()
        val temp = File(context.cacheDir, "jarvis-offline-import.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected model" }
            temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
        }
        require(temp.length() >= MIN_MODEL_BYTES) {
            "Selected file is too small to be a supported JARVIS local LLM model."
        }
        if (modelFile.exists() && !modelFile.delete()) {
            temp.delete()
            error("Unable to replace the existing offline model")
        }
        if (!temp.renameTo(modelFile)) {
            temp.copyTo(modelFile, overwrite = true)
            temp.delete()
        }
        status()
    }

    suspend fun generate(message: String, deviceContext: String = ""): String? = withContext(Dispatchers.IO) {
        val status = status()
        if (!status.installed || !status.compatible) return@withContext null

        val memoryContext = memory.contextFor(message, limit = 5, maxChars = 4200)
        val prompt = buildString {
            append("You are JARVIS running completely offline on the Chairman's Android device. ")
            append("Be concise, practical, and explicit when information may be stale because there is no internet. ")
            append("Never claim that you checked a live source while offline.\n\n")
            if (deviceContext.isNotBlank()) {
                append("DEVICE CONTEXT:\n").append(deviceContext.take(2200)).append("\n\n")
            }
            if (memoryContext.isNotBlank()) {
                append(memoryContext).append("\n\n")
            }
            append("CHAIRMAN:\n").append(message.take(6000)).append("\n\nJARVIS:\n")
        }

        try {
            getOrCreateEngine().generateResponse(prompt).trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            shutdown()
            null
        }
    }

    fun memoryOnlyAnswer(message: String): String {
        val records = memory.search(message, limit = 5)
        if (records.isEmpty()) {
            return "I'm offline and I don't have enough stored knowledge for that yet. Connect to the internet so I can answer and retain useful knowledge for later."
        }
        val body = records.joinToString("\n") { record ->
            val source = record.sourceUrl?.let { " ($it)" }.orEmpty()
            "• ${record.topic}: ${record.content.take(700)}$source"
        }
        return "I'm offline. Here's what I remember locally:\n$body"
    }

    fun shutdown() {
        synchronized(engineLock) {
            runCatching { engine?.close() }
            engine = null
        }
    }

    private fun getOrCreateEngine(): LlmInference {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .setMaxTopK(40)
                .build()
            return LlmInference.createFromOptions(context.applicationContext, options).also { engine = it }
        }
    }

    companion object {
        private const val MODEL_FILE_NAME = "jarvis-offline.task"
        private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
        private const val MIN_TOTAL_RAM_BYTES = 3L * 1024L * 1024L * 1024L
    }
}
