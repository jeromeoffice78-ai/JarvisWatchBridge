package com.jarvis.watchbridge.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

enum class JarvisAudioRoute {
    DEVICE,
    AUTO,
    BLUETOOTH
}

class JarvisVoiceController(private val context: Context) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ttsReady = false
    private var activeRoute = JarvisAudioRoute.DEVICE

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(0.98f)
        }
    }

    fun availableRoutes(): List<JarvisAudioRoute> {
        val hasBluetooth = audioManager.getDevices(AudioManager.GET_DEVICES_ALL).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        return if (hasBluetooth) {
            listOf(JarvisAudioRoute.DEVICE, JarvisAudioRoute.AUTO, JarvisAudioRoute.BLUETOOTH)
        } else {
            listOf(JarvisAudioRoute.DEVICE, JarvisAudioRoute.AUTO)
        }
    }

    fun setRoute(route: JarvisAudioRoute): Boolean {
        activeRoute = route
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.clearCommunicationDevice()
            val devices = audioManager.availableCommunicationDevices
            val target = when (route) {
                JarvisAudioRoute.DEVICE -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                JarvisAudioRoute.BLUETOOTH -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
                JarvisAudioRoute.AUTO -> null
            }
            return target?.let { audioManager.setCommunicationDevice(it) } ?: (route == JarvisAudioRoute.AUTO)
        }

        @Suppress("DEPRECATION")
        when (route) {
            JarvisAudioRoute.DEVICE -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
            JarvisAudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            JarvisAudioRoute.AUTO -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
        }
        return true
    }

    fun currentRoute(): JarvisAudioRoute = activeRoute

    fun listen(
        onListening: () -> Unit,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device.")
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListening()
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    if (text.isNotBlank()) onResult(text) else onError("I didn't catch that.")
                    stopListening()
                }

                override fun onError(error: Int) {
                    onError(recognitionError(error))
                    stopListening()
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speech.startListening(intent)
        }
    }

    fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!ttsReady) {
            onError("Text-to-speech is not ready yet.")
            return
        }
        val utteranceId = "jarvis-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = onStart()
            override fun onDone(id: String?) = onDone()
            override fun onError(id: String?) = onError("JARVIS voice playback failed.")
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun recognitionError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
        SpeechRecognizer.ERROR_CLIENT -> "Voice session stopped."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition network error."
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognition is busy. Try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear any speech."
        else -> "Voice recognition error ($code)."
    }
}
