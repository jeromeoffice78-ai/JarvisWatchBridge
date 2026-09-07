package com.jarvis.watchbridge.mood

import android.content.Context
import java.util.Locale
import kotlin.math.min

enum class MoodLabel(val displayName: String) {
    NEUTRAL("Neutral"),
    FOCUSED("Focused"),
    FRUSTRATED("Frustrated"),
    STRESSED("Stressed"),
    LOW("Low"),
    UPBEAT("Upbeat"),
    TIRED("Tired")
}

data class MoodSnapshot(
    val label: MoodLabel,
    val confidence: Float,
    val source: String,
    val responseTone: String,
    val detailLevel: String,
    val speechRate: Float,
    val speechPitch: Float,
    val proactive: Boolean
)

/**
 * Privacy-preserving interaction-style adaptation.
 *
 * Important:
 * - This estimates conversational tone; it does not diagnose emotions or mental health.
 * - Raw user messages are never persisted.
 * - Only aggregate label counts, preferences, and the last estimated label are stored.
 * - Passwords, banking secrets, biometrics, and other credentials are never accepted or stored here.
 */
class MoodEngine(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAdaptiveEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setAdaptiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun resetLearning() {
        val enabled = isAdaptiveEnabled()
        prefs.edit().clear().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun handleControlCommand(message: String): String? {
        val normalized = normalize(message)

        if (normalized.contains("stop adapting to my mood") ||
            normalized.contains("stop mood adaptation") ||
            normalized.contains("turn off mood adaptation")) {
            setAdaptiveEnabled(false)
            return "Mood adaptation is off. I will use a neutral response style until you turn it back on."
        }

        if (normalized.contains("start adapting to my mood") ||
            normalized.contains("turn on mood adaptation") ||
            normalized.contains("enable mood adaptation")) {
            setAdaptiveEnabled(true)
            return "Mood adaptation is on. I will adjust my response style from conversational cues while treating them as estimates."
        }

        if (normalized.contains("reset mood learning") ||
            normalized.contains("reset what you learned about my mood") ||
            normalized.contains("forget my mood preferences")) {
            resetLearning()
            return "Mood-learning preferences have been reset. I will start again from a neutral style."
        }

        correctionFromExplicitStatement(normalized)?.let { corrected ->
            setAdaptiveEnabled(true)
            rememberConfirmed(corrected)
            return "Understood. I will treat your current interaction style as ${corrected.displayName.lowercase(Locale.US)}."
        }

        if (normalized.contains("i'm not angry") ||
            normalized.contains("i am not angry") ||
            normalized.contains("i'm not mad") ||
            normalized.contains("i am not mad") ||
            normalized.contains("i'm fine") ||
            normalized.contains("i am fine")) {
            rememberConfirmed(MoodLabel.NEUTRAL)
            return "Understood. I will use a neutral response style."
        }

        return null
    }

    fun observe(message: String): MoodSnapshot {
        if (!isAdaptiveEnabled()) return profileFor(MoodLabel.NEUTRAL, 1.0f, "disabled")

        val normalized = normalize(message)
        val explicit = correctionFromExplicitStatement(normalized)
        if (explicit != null) {
            rememberConfirmed(explicit)
            return profileFor(explicit, 0.98f, "explicit")
        }

        val scores = linkedMapOf(
            MoodLabel.FRUSTRATED to scoreTerms(normalized, FRUSTRATED_TERMS),
            MoodLabel.STRESSED to scoreTerms(normalized, STRESSED_TERMS),
            MoodLabel.LOW to scoreTerms(normalized, LOW_TERMS),
            MoodLabel.UPBEAT to scoreTerms(normalized, UPBEAT_TERMS),
            MoodLabel.TIRED to scoreTerms(normalized, TIRED_TERMS),
            MoodLabel.FOCUSED to scoreTerms(normalized, FOCUSED_TERMS)
        )

        val letters = message.count { it.isLetter() }.coerceAtLeast(1)
        val upperRatio = message.count { it.isUpperCase() }.toFloat() / letters
        val capsBoost = if (message.length >= 8 && letters >= 6 && upperRatio >= 0.7f) 1 else 0
        val punctuationBoost = if (message.count { it == '!' } >= 2) 1 else 0

        if (capsBoost + punctuationBoost > 0) {
            scores[MoodLabel.FRUSTRATED] = (scores[MoodLabel.FRUSTRATED] ?: 0) + 1
        }

        val best = scores.maxByOrNull { it.value }
        val label = if (best == null || best.value <= 0) MoodLabel.NEUTRAL else best.key
        val score = best?.value ?: 0

        val confidence = when {
            label == MoodLabel.NEUTRAL -> 0.55f
            score >= 3 -> 0.82f
            score == 2 -> 0.70f
            else -> 0.58f
        }

        rememberObservation(label)
        return profileFor(label, confidence, "estimated")
    }

    fun responseDirective(snapshot: MoodSnapshot): String {
        if (!isAdaptiveEnabled()) {
            return "JARVIS response style: neutral. Mood adaptation is disabled."
        }

        return buildString {
            append("JARVIS interaction-style guidance. ")
            append("Treat this as a conversational estimate, not a factual statement about the user's emotions or mental health. ")
            append("Estimated interaction tone: ${snapshot.label.displayName} ")
            append("(confidence ${(snapshot.confidence * 100).toInt()}%). ")
            append("Response tone: ${snapshot.responseTone}. ")
            append("Detail level: ${snapshot.detailLevel}. ")
            append(if (snapshot.proactive) "Be proactively helpful. " else "Avoid unnecessary proactive suggestions. ")
            append("Do not mention the estimate unless it is directly useful. ")
            append("If the user explicitly states how they feel, their statement overrides this estimate.")
        }
    }

    private fun profileFor(label: MoodLabel, confidence: Float, source: String): MoodSnapshot =
        when (label) {
            MoodLabel.FRUSTRATED -> MoodSnapshot(
                label, confidence, source,
                "calm, direct, highly task-focused, no filler",
                "short with concrete next actions", 0.92f, 0.96f, true
            )
            MoodLabel.STRESSED -> MoodSnapshot(
                label, confidence, source,
                "calm, structured, reassuring without being patronizing",
                "short, ordered, one action at a time", 0.88f, 0.96f, true
            )
            MoodLabel.LOW -> MoodSnapshot(
                label, confidence, source,
                "warm, respectful, low-pressure",
                "moderate and easy to follow", 0.88f, 0.98f, false
            )
            MoodLabel.UPBEAT -> MoodSnapshot(
                label, confidence, source,
                "energetic, positive, efficient",
                "moderate", 1.05f, 1.03f, true
            )
            MoodLabel.TIRED -> MoodSnapshot(
                label, confidence, source,
                "clear, gentle, minimal cognitive load",
                "very concise", 0.86f, 0.97f, true
            )
            MoodLabel.FOCUSED -> MoodSnapshot(
                label, confidence, source,
                "executive, concise, action-oriented",
                "concise but complete", 1.00f, 1.00f, true
            )
            MoodLabel.NEUTRAL -> MoodSnapshot(
                label, confidence, source,
                "balanced, professional, conversational",
                "concise", 1.00f, 1.00f, true
            )
        }

    private fun rememberObservation(label: MoodLabel) {
        val key = "count_${label.name}"
        val count = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(key, min(count + 1, 10_000))
            .putString(KEY_LAST_LABEL, label.name)
            .apply()
    }

    private fun rememberConfirmed(label: MoodLabel) {
        val key = "confirmed_${label.name}"
        val count = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(key, min(count + 1, 10_000))
            .putString(KEY_LAST_LABEL, label.name)
            .apply()
    }

    private fun correctionFromExplicitStatement(normalized: String): MoodLabel? =
        when {
            containsAny(normalized, listOf("i'm frustrated", "i am frustrated", "i'm annoyed", "i am annoyed")) -> MoodLabel.FRUSTRATED
            containsAny(normalized, listOf("i'm stressed", "i am stressed", "i'm overwhelmed", "i am overwhelmed", "i'm worried", "i am worried")) -> MoodLabel.STRESSED
            containsAny(normalized, listOf("i'm sad", "i am sad", "i'm down", "i am down", "i feel low")) -> MoodLabel.LOW
            containsAny(normalized, listOf("i'm happy", "i am happy", "i'm excited", "i am excited", "i feel great")) -> MoodLabel.UPBEAT
            containsAny(normalized, listOf("i'm tired", "i am tired", "i'm exhausted", "i am exhausted", "i'm sleepy", "i am sleepy")) -> MoodLabel.TIRED
            containsAny(normalized, listOf("business mode", "focus mode", "let's get this done", "lets get this done")) -> MoodLabel.FOCUSED
            else -> null
        }

    private fun scoreTerms(text: String, terms: List<String>): Int = terms.count { text.contains(it) }
    private fun containsAny(text: String, terms: List<String>): Boolean = terms.any { text.contains(it) }
    private fun normalize(text: String): String = text.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val PREFS_NAME = "jarvis_mood_adaptation"
        private const val KEY_ENABLED = "adaptive_enabled"
        private const val KEY_LAST_LABEL = "last_label"

        private val FRUSTRATED_TERMS = listOf(
            "frustrated", "annoyed", "irritated", "mad", "pissed",
            "not working", "still not working", "fix it", "this is wrong"
        )
        private val STRESSED_TERMS = listOf(
            "stressed", "overwhelmed", "worried", "anxious", "too much",
            "can't deal", "cannot deal"
        )
        private val LOW_TERMS = listOf(
            "sad", "feeling down", "feel down", "hurt", "feel low", "rough day"
        )
        private val UPBEAT_TERMS = listOf(
            "happy", "excited", "great", "awesome", "good mood", "love it"
        )
        private val TIRED_TERMS = listOf(
            "tired", "exhausted", "sleepy", "worn out", "long day"
        )
        private val FOCUSED_TERMS = listOf(
            "business mode", "focus mode", "do it", "continue", "execute",
            "finish it", "fast", "get it done"
        )
    }
}
