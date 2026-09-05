package com.jarvis.watchbridge.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class SpeechOutput(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    @Volatile private var listener: ((Boolean) -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { listener?.invoke(true) }
            override fun onDone(utteranceId: String?) { listener?.invoke(false) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { listener?.invoke(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { listener?.invoke(false) }
        })
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setSpeechRate(1.0f)
            tts.setPitch(0.96f)
        }
    }

    fun setSpeakingListener(onSpeakingChanged: (Boolean) -> Unit) {
        listener = onSpeakingChanged
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val utteranceId = "jarvis-main-${System.currentTimeMillis()}"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    fun stop() {
        tts.stop()
        listener?.invoke(false)
    }

    fun shutdown() {
        tts.stop()
        listener?.invoke(false)
        tts.shutdown()
    }
}
