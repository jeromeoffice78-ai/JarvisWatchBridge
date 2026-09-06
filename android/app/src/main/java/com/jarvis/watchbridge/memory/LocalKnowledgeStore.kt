package com.jarvis.watchbridge.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

/**
 * Durable, device-local JARVIS knowledge store.
 *
 * The vault is intentionally independent of the cloud backend so learned knowledge remains
 * available with airplane mode enabled and after app/process restarts. Sensitive-looking content
 * is not automatically persisted; explicit user memory commands can still be handled separately.
 */
class LocalKnowledgeStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    data class KnowledgeRecord(
        val id: Long,
        val topic: String,
        val content: String,
        val sourceUrl: String?,
        val createdAt: Long,
        val score: Int = 0
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE knowledge (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                topic TEXT NOT NULL,
                content TEXT NOT NULL,
                source_url TEXT,
                content_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_knowledge_created_at ON knowledge(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun remember(topic: String, content: String, sourceUrl: String? = null): Boolean {
        val cleanTopic = topic.trim().take(300)
        val cleanContent = content.trim().take(MAX_RECORD_CHARS)
        if (cleanContent.isBlank() || looksSensitive(cleanContent)) return false

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("topic", cleanTopic.ifBlank { "JARVIS knowledge" })
            put("content", cleanContent)
            put("source_url", sourceUrl?.trim()?.take(2000))
            put("content_hash", sha256("$cleanTopic\n$cleanContent"))
            put("created_at", now)
            put("last_used_at", now)
        }
        return writableDatabase.insertWithOnConflict(
            "knowledge",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun learnDocument(topic: String, text: String, sourceUrl: String? = null): Int {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return 0
        var stored = 0
        var start = 0
        while (start < clean.length) {
            val end = (start + CHUNK_SIZE).coerceAtMost(clean.length)
            val chunk = clean.substring(start, end)
            if (remember(topic, chunk, sourceUrl)) stored++
            if (end >= clean.length) break
            start = max(end - CHUNK_OVERLAP, start + 1)
        }
        return stored
    }

    fun search(query: String, limit: Int = 6): List<KnowledgeRecord> {
        val terms = tokenize(query)
        val candidates = mutableListOf<KnowledgeRecord>()
        readableDatabase.query(
            "knowledge",
            arrayOf("id", "topic", "content", "source_url", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            "500"
        ).use { cursor ->
            val now = System.currentTimeMillis()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val topic = cursor.getString(1)
                val content = cursor.getString(2)
                val source = cursor.getString(3)
                val createdAt = cursor.getLong(4)
                val haystack = "${topic.lowercase(Locale.US)} ${content.lowercase(Locale.US)}"
                var score = 0
                for (term in terms) {
                    if (topic.lowercase(Locale.US).contains(term)) score += 5
                    val occurrences = Regex("\\b${Regex.escape(term)}\\b").findAll(haystack).count()
                    score += occurrences.coerceAtMost(6) * 2
                }
                val ageDays = ((now - createdAt).coerceAtLeast(0L) / 86_400_000L).toInt()
                if (ageDays <= 7) score += 2 else if (ageDays <= 30) score += 1
                if (terms.isEmpty()) score = 1
                if (score > 0) {
                    candidates += KnowledgeRecord(id, topic, content, source, createdAt, score)
                }
            }
        }

        val best = candidates
            .sortedWith(compareByDescending<KnowledgeRecord> { it.score }.thenByDescending { it.createdAt })
            .take(limit.coerceIn(1, 20))

        if (best.isNotEmpty()) {
            val ids = best.joinToString(",") { it.id.toString() }
            writableDatabase.execSQL(
                "UPDATE knowledge SET last_used_at=? WHERE id IN ($ids)",
                arrayOf(System.currentTimeMillis())
            )
        }
        return best
    }

    fun contextFor(query: String, limit: Int = 6, maxChars: Int = 7000): String {
        val records = search(query, limit)
        if (records.isEmpty()) return ""
        val builder = StringBuilder("JARVIS LOCAL MEMORY:\n")
        for (record in records) {
            val source = record.sourceUrl?.let { " [source: $it]" }.orEmpty()
            val entry = "- ${record.topic}$source: ${record.content}\n"
            if (builder.length + entry.length > maxChars) break
            builder.append(entry)
        }
        return builder.toString().trim()
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM knowledge", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun clearAll() {
        writableDatabase.delete("knowledge", null, null)
    }

    private fun tokenize(text: String): Set<String> = text
        .lowercase(Locale.US)
        .split(Regex("[^a-z0-9]+"))
        .asSequence()
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .take(40)
        .toSet()

    private fun looksSensitive(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return SENSITIVE_MARKERS.any { lower.contains(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val DATABASE_NAME = "jarvis_knowledge.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_RECORD_CHARS = 12_000
        private const val CHUNK_SIZE = 1_800
        private const val CHUNK_OVERLAP = 180

        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "with", "from", "have", "what", "when",
            "where", "which", "will", "your", "you", "are", "was", "were", "can", "could",
            "would", "should", "about", "into", "than", "then", "them", "they", "their"
        )
        private val SENSITIVE_MARKERS = setOf(
            "password", "passcode", "pin number", "security code", "cvv", "credit card number",
            "social security number", "ssn:", "private key", "secret key", "api key", "recovery phrase",
            "seed phrase"
        )
    }
}
