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
 * Optional fully on-device generation engine.
 *
 * A model is deliberately not bundled in the APK because mobile LLM model files are hundreds of
 * megabytes and may have separate license terms. The user can import a compatible MediaPipe .task
 * model once; after that, inference is completely offline.
 */
class OfflineBrain(
    private val context: Context,
    private val memory: LocalKnowledgeStore
) {
    data class Status(
        val installed: Boolean,
        val compatible: Boolean,
        val modelSizeBytes: Long,
        val message: String
    )

    private val modelDir = File(context.filesDir, "models")
    private val modelFile = File(modelDir, MODEL_FILE_NAME)
    @Volatile private var engine: LlmInference? = null
    private val engineLock = Any()

    fun status(): Status {
        val installed = modelFile.exists() && modelFile.length() >= MIN_MODEL_BYTES
        val totalRam = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }.totalMem
        val compatible = totalRam >= MIN_TOTAL_RAM_BYTES
        val message = when {
            !installed -> "Offline knowledge memory is active. Import a compatible .task model for full offline generation."
            !compatible -> "Offline model is installed, but this device may not have enough RAM for reliable generation. Memory-only offline mode remains available."
            else -> "Full offline JARVIS generation is ready."
        }
        return Status(installed, compatible, if (modelFile.exists()) modelFile.length() else 0L, message)
    }

    suspend fun installModel(uri: Uri): Status = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        val temp = File(modelDir, "$MODEL_FILE_NAME.partial")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            } ?: error("Could not open selected model")
            require(temp.length() >= MIN_MODEL_BYTES) { "Selected file is too small to be a supported local LLM model" }
            shutdown()
            if (modelFile.exists() && !modelFile.delete()) error("Could not replace previous offline model")
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                temp.delete()
            }
        }.getOrElse {
            temp.delete()
            throw it
        }
        status()
    }

    suspend fun generate(userMessage: String, extraContext: String = ""): String? = withContext(Dispatchers.Default) {
        val current = status()
        if (!current.installed || !current.compatible) return@withContext null

        val memoryContext = memory.contextFor(userMessage, limit = 7, maxChars = 6500)
        val prompt = buildString {
            append("You are JARVIS running entirely offline on the user's Android device. ")
            append("Be accurate, concise, and candid when local memory does not contain enough information. ")
            append("Never claim to have searched the internet while offline.\n\n")
            if (memoryContext.isNotBlank()) append(memoryContext).append("\n\n")
            if (extraContext.isNotBlank()) append("CURRENT DEVICE CONTEXT:\n").append(extraContext.take(2500)).append("\n\n")
            append("USER:\n").append(userMessage.take(4000)).append("\n\nJARVIS:")
        }

        runCatching {
            val llm = getOrCreateEngine()
            llm.generateResponse(prompt).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun memoryOnlyAnswer(userMessage: String): String {
        val records = memory.search(userMessage, limit = 5)
        if (records.isEmpty()) {
            return "I'm offline. I can still use device controls and local functions, but I don't have enough saved knowledge about that yet. Connect once so I can research and remember it, or import an offline model."
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
                .setTemperature(0.35f)
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
