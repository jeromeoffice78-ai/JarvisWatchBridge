package com.jarvis.watchbridge.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.jarvis.watchbridge.ai.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AlwaysListeningService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val chat = ChatRepository()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var awaitingCommand = false
    private var speaking = false
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, foregroundNotification("Say “Jarvis” to wake me"))
        started = true
        startListeningSoon(250)
        return START_STICKY
    }

    private fun listenIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
    }

    private fun startListeningSoon(delayMs: Long = 500) {
        if (!started || speaking || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            try {
                recognizer?.cancel()
                recognizer?.startListening(listenIntent())
            } catch (_: Exception) {
                startListeningSoon(1200)
            }
        }, delayMs)
    }

    private fun handleText(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        val lower = text.lowercase(Locale.getDefault())
        val wakeIndex = lower.indexOf("jarvis")

        when {
            awaitingCommand -> {
                awaitingCommand = false
                processCommand(text)
            }
            wakeIndex >= 0 -> {
                val command = text.substring(wakeIndex + "jarvis".length).trim(' ', ',', '.', ':', ';', '-')
                if (command.isBlank()) {
                    awaitingCommand = true
                    speak("Yes?")
                } else {
                    processCommand(command)
                }
            }
        }
    }

    private fun processCommand(command: String) {
        updateNotification("Processing: ${command.take(60)}")
        serviceScope.launch {
            val reply = try {
                chat.send(command, "Voice command received from this Android device.")
            } catch (e: Exception) {
                "I couldn't reach the JARVIS service. ${e.message ?: "Please try again."}"
            }
            sendBroadcast(Intent(ACTION_JARVIS_REPLY).setPackage(packageName).putExtra(EXTRA_COMMAND, command).putExtra(EXTRA_REPLY, reply))
            speak(reply)
        }
    }

    private fun speak(text: String) {
        speaking = true
        recognizer?.cancel()
        updateNotification("JARVIS speaking")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.currentTimeMillis()}")
        // Speech duration is engine-dependent. Poll until synthesis is done, then resume wake listening.
        handler.post(object : Runnable {
            override fun run() {
                val engine = tts
                if (engine != null && engine.isSpeaking) {
                    handler.postDelayed(this, 250)
                } else {
                    speaking = false
                    updateNotification("Say “Jarvis” to wake me")
                    startListeningSoon(350)
                }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    override fun onResults(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::handleText)
        startListeningSoon(350)
    }

    override fun onError(error: Int) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            updateNotification("Microphone permission required")
            return
        }
        startListeningSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 450)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val first = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (!awaitingCommand && first.lowercase(Locale.getDefault()).contains("jarvis")) {
            updateNotification("JARVIS heard the wake word")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS Always Listening", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun foregroundNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS voice mode")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, foregroundNotification(text))
    }

    override fun onDestroy() {
        started = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarvis.watchbridge.voice.START"
        const val ACTION_STOP = "com.jarvis.watchbridge.voice.STOP"
        const val ACTION_JARVIS_REPLY = "com.jarvis.watchbridge.voice.REPLY"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_REPLY = "reply"
        private const val CHANNEL_ID = "jarvis_always_listening"
        private const val NOTIFICATION_ID = 1978
    }
}
