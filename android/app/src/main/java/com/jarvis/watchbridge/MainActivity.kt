package com.jarvis.watchbridge

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.jarvis.watchbridge.ai.ChatRepository
import com.jarvis.watchbridge.audio.AudioRouter
import com.jarvis.watchbridge.ble.BleManager
import com.jarvis.watchbridge.health.HealthRepository
import com.jarvis.watchbridge.notifications.NotificationHelper
import com.jarvis.watchbridge.notifications.PhoneMessageRepository
import com.jarvis.watchbridge.ui.JarvisFace
import com.jarvis.watchbridge.ui.JarvisVisualState
import com.jarvis.watchbridge.voice.AlwaysListeningService
import com.jarvis.watchbridge.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var ble: BleManager
    private lateinit var health: HealthRepository
    private val chat = ChatRepository()
    private lateinit var notifications: NotificationHelper
    private lateinit var audioRouter: AudioRouter
    private lateinit var speech: SpeechOutput
    private val phoneMessages = PhoneMessageRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(this)
        health = HealthRepository(this)
        notifications = NotificationHelper(this)
        audioRouter = AudioRouter(this)
        speech = SpeechOutput(this)

        startPhoneMessageSync()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by ble.state.collectAsState()
                var prompt by remember { mutableStateOf("") }
                var reply by remember { mutableStateOf("JARVIS online") }
                var healthText by remember { mutableStateOf("Health data not loaded") }
                var busy by remember { mutableStateOf(false) }
                var listening by remember { mutableStateOf(false) }
                var alwaysListening by remember { mutableStateOf(false) }
                var alertPulse by remember { mutableStateOf(false) }
                var routes by remember { mutableStateOf(audioRouter.availableRoutes()) }
                var selectedRoute by remember { mutableStateOf("Device audio") }

                val visualState = when {
                    alertPulse -> JarvisVisualState.ALERT
                    busy -> JarvisVisualState.THINKING
                    listening || alwaysListening -> JarvisVisualState.LISTENING
                    reply.isNotBlank() && reply != "JARVIS online" -> JarvisVisualState.SPEAKING
                    else -> JarvisVisualState.IDLE
                }

                LaunchedEffect(alertPulse) {
                    if (alertPulse) {
                        delay(4_000)
                        alertPulse = false
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("JARVIS WATCH BRIDGE", style = MaterialTheme.typography.headlineSmall)
                            JarvisFace(state = visualState)
                            Text(
                                when (visualState) {
                                    JarvisVisualState.IDLE -> "JARVIS standing by"
                                    JarvisVisualState.LISTENING -> if (alwaysListening) "Always listening for ‘Jarvis’" else "Listening"
                                    JarvisVisualState.THINKING -> "Processing"
                                    JarvisVisualState.SPEAKING -> "Speaking"
                                    JarvisVisualState.ALERT -> "Incoming JARVIS alert"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("Audio: $selectedRoute")
                            Text(state.connectedName?.let { "Connected: $it" } ?: "No watch connected")
                            state.heartRateBpm?.let { Text("Direct BLE heart rate: $it bpm") }
                            state.error?.let { Text(it) }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    permissionLauncher.launch(arrayOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                        Manifest.permission.ACTIVITY_RECOGNITION
                                    ))
                                }) { Text("Permissions") }
                                Button(onClick = { if (state.scanning) ble.stopScan() else ble.startScan() }) {
                                    Text(if (state.scanning) "Stop scan" else "Scan watches")
                                }
                            }
                        }
                        items(state.devices) { d ->
                            ElevatedCard(onClick = { ble.connect(d.address) }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(d.name)
                                    Text(d.address, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("Voice & audio", style = MaterialTheme.typography.titleLarge)
                            Text("Phone/tablet microphone and speaker are the default. Compatible Bluetooth earbuds, headsets, speakers, or watches appear below when Android exposes them as communication audio devices.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    audioRouter.useDeviceAudio()
                                    selectedRoute = "Device audio"
                                }) { Text("Use device") }
                                Button(onClick = { routes = audioRouter.availableRoutes() }) { Text("Refresh audio") }
                            }
                            routes.filter { it.id >= 0 }.forEach { route ->
                                TextButton(onClick = {
                                    if (audioRouter.useRoute(route.id)) selectedRoute = route.name
                                }) { Text("Use ${route.name}") }
                            }
                            Button(onClick = {
                                if (!alwaysListening) {
                                    val intent = Intent(this@MainActivity, AlwaysListeningService::class.java)
                                        .setAction(AlwaysListeningService.ACTION_START)
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                    alwaysListening = true
                                } else {
                                    stopService(Intent(this@MainActivity, AlwaysListeningService::class.java))
                                    alwaysListening = false
                                }
                            }) {
                                Text(if (alwaysListening) "Stop always listening" else "Enable ‘Jarvis’ wake word")
                            }
                            Text("Always-listening mode runs as a visible Android microphone foreground service. Android will show a persistent notification while the microphone service is active.", style = MaterialTheme.typography.bodySmall)
                        }
                        item {
                            HorizontalDivider()
                            Text("Health", style = MaterialTheme.typography.titleLarge)
                            Text(healthText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { healthLauncher.launch(health.permissions) }) { Text("Health access") }
                                Button(onClick = {
                                    lifecycleScope.launch {
                                        healthText = try {
                                            if (health.hasPermissions()) health.snapshot() else "Grant Health Connect permissions first"
                                        } catch (e: Exception) { "Health Connect unavailable: ${e.message}" }
                                    }
                                }) { Text("Refresh health") }
                            }
                        }
                        item {
                            HorizontalDivider()
                            Text("Ask JARVIS", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(prompt, { prompt = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { listening = !listening },
                                    colors = if (listening) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary) else ButtonDefaults.buttonColors()
                                ) { Text(if (listening) "Stop listening" else "Listen") }
                                Button(enabled = !busy && prompt.isNotBlank(), onClick = {
                                    val msg = prompt
                                    listening = false
                                    busy = true
                                    lifecycleScope.launch {
                                        reply = try { chat.send(msg, healthText) } catch (e: Exception) { "Error: ${e.message}" }
                                        notifications.push("JARVIS", reply)
                                        speech.speak(reply)
                                        busy = false
                                    }
                                }) { Text(if (busy) "Thinking…" else "Send") }
                            }
                            Text(reply)
                        }
                        item {
                            HorizontalDivider()
                            Text("Phone receptionist", style = MaterialTheme.typography.titleLarge)
                            Text("JARVIS checks for completed Vapi calls while Watch Bridge is running and posts a phone notification. If LAXASFIT mirrors phone notifications, the alert can appear on the watch too.")
                            Button(onClick = { alertPulse = true }) { Text("Preview face alert") }
                        }
                        item {
                            Text("Portable mode is enabled: BLE is optional, so JARVIS can run on compatible Android phones or tablets even when no watch is present. Watch-only capabilities remain dependent on the hardware and firmware exposed by that watch.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speech.shutdown()
        audioRouter.clearRoute()
        super.onDestroy()
    }

    private fun startPhoneMessageSync() {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("jarvis_phone_sync", MODE_PRIVATE)
            while (isActive) {
                try {
                    val latest = withContext(Dispatchers.IO) { phoneMessages.latest() }
                    if (latest != null && latest.id.isNotBlank()) {
                        val lastSeen = prefs.getString("last_message_id", null)
                        if (lastSeen == null) {
                            prefs.edit().putString("last_message_id", latest.id).apply()
                        } else if (lastSeen != latest.id) {
                            val caller = latest.callerPhone ?: "Unknown caller"
                            notifications.push("📞 JARVIS call message", "$caller — ${latest.summary}")
                            prefs.edit().putString("last_message_id", latest.id).apply()
                        }
                    }
                } catch (_: Exception) {
                    // Keep the sync loop alive; a later poll can recover automatically.
                }
                delay(30_000)
            }
        }
    }
}
