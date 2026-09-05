package com.jarvis.watchbridge

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.jarvis.watchbridge.ui.JarvisPortrait
import com.jarvis.watchbridge.ui.JarvisVisualState
import com.jarvis.watchbridge.voice.AlwaysListeningService
import com.jarvis.watchbridge.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val JarvisBlue = Color(0xFF59C9FF)
private val JarvisBlueDeep = Color(0xFF0B2944)
private val JarvisPanel = Color(0xFF101826)
private val JarvisPanel2 = Color(0xFF162234)
private val JarvisText = Color(0xFFF3F8FF)
private val JarvisMuted = Color(0xFFA8BDD0)

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = JarvisBlue,
                    secondary = Color(0xFF8DDCFF),
                    background = Color(0xFF070B12),
                    surface = JarvisPanel,
                    onSurface = JarvisText
                )
            ) {
                val state by ble.state.collectAsState()
                var prompt by remember { mutableStateOf("") }
                var reply by remember { mutableStateOf("Good day, Chairman. JARVIS is online.") }
                var healthText by remember { mutableStateOf("Health data ready when requested") }
                var busy by remember { mutableStateOf(false) }
                var listening by remember { mutableStateOf(false) }
                var alwaysListening by remember { mutableStateOf(false) }
                var isSpeaking by remember { mutableStateOf(false) }
                var alertPulse by remember { mutableStateOf(false) }
                var routes by remember { mutableStateOf(audioRouter.availableRoutes()) }
                var selectedRoute by remember { mutableStateOf("Device audio") }
                var showSystems by remember { mutableStateOf(false) }
                var showHealth by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    speech.setSpeakingListener { speaking -> runOnUiThread { isSpeaking = speaking } }
                    onDispose { speech.setSpeakingListener { } }
                }

                val visualState = when {
                    alertPulse -> JarvisVisualState.ALERT
                    busy -> JarvisVisualState.THINKING
                    isSpeaking -> JarvisVisualState.SPEAKING
                    listening || alwaysListening -> JarvisVisualState.LISTENING
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

                Surface(Modifier.fillMaxSize(), color = Color(0xFF070B12)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text("JARVIS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = JarvisText)
                            Text("CHAIRMAN COMMAND CENTER", color = JarvisBlue, style = MaterialTheme.typography.labelLarge)
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Column(
                                    Modifier
                                        .background(Brush.verticalGradient(listOf(JarvisBlueDeep, JarvisPanel, Color(0xFF0A111C))))
                                        .padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    JarvisPortrait(state = visualState)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        when (visualState) {
                                            JarvisVisualState.IDLE -> "STANDING BY"
                                            JarvisVisualState.LISTENING -> if (alwaysListening) "WAKE WORD ACTIVE" else "LISTENING"
                                            JarvisVisualState.THINKING -> "PROCESSING"
                                            JarvisVisualState.SPEAKING -> "SPEAKING"
                                            JarvisVisualState.ALERT -> "ALERT"
                                        },
                                        color = JarvisBlue,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(reply, color = JarvisText, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }

                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusTile("WATCH", state.connectedName ?: "Not connected", Modifier.weight(1f))
                                StatusTile("AUDIO", selectedRoute, Modifier.weight(1f))
                            }
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = JarvisPanel)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Talk to JARVIS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    OutlinedTextField(
                                        value = prompt,
                                        onValueChange = { prompt = it },
                                        placeholder = { Text("Ask anything or give a command") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = { listening = !listening },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(16.dp)
                                        ) { Text(if (listening) "Stop" else "Listen") }
                                        Button(
                                            enabled = !busy && prompt.isNotBlank(),
                                            onClick = {
                                                val msg = prompt
                                                listening = false
                                                busy = true
                                                lifecycleScope.launch {
                                                    reply = try { chat.send(msg, healthText) } catch (e: Exception) { "I hit a connection problem: ${e.message ?: "unknown error"}" }
                                                    busy = false
                                                    notifications.push("JARVIS", reply)
                                                    speech.speak(reply)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(16.dp)
                                        ) { Text(if (busy) "Thinking…" else "Send") }
                                    }
                                    Button(
                                        onClick = {
                                            if (!alwaysListening) {
                                                val intent = Intent(this@MainActivity, AlwaysListeningService::class.java)
                                                    .setAction(AlwaysListeningService.ACTION_START)
                                                ContextCompat.startForegroundService(this@MainActivity, intent)
                                                alwaysListening = true
                                            } else {
                                                stopService(Intent(this@MainActivity, AlwaysListeningService::class.java))
                                                alwaysListening = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = JarvisPanel2)
                                    ) {
                                        Text(if (alwaysListening) "Disable always-listening" else "Enable ‘Jarvis’ wake word")
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = JarvisPanel)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Quick status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(state.heartRateBpm?.let { "Heart rate: $it bpm" } ?: "Heart rate: waiting for watch", color = JarvisMuted)
                                    Text("Phone receptionist: active while JARVIS is running", color = JarvisMuted)
                                    Text("Wake service: ${if (alwaysListening) "active" else "off"}", color = JarvisMuted)
                                    state.error?.let { Text("Watch: $it", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = { showHealth = !showHealth },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisPanel2)
                            ) { Text(if (showHealth) "Hide health controls" else "Health & wellness") }
                        }

                        if (showHealth) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = JarvisPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(healthText)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { healthLauncher.launch(health.permissions) }) { Text("Grant access") }
                                            Button(onClick = {
                                                lifecycleScope.launch {
                                                    healthText = try {
                                                        if (health.hasPermissions()) health.snapshot() else "Grant Health Connect permissions first"
                                                    } catch (e: Exception) { "Health unavailable: ${e.message}" }
                                                }
                                            }) { Text("Refresh") }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = { showSystems = !showSystems },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisPanel2)
                            ) { Text(if (showSystems) "Hide system controls" else "System controls") }
                        }

                        if (showSystems) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = JarvisPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Watch & permissions", style = MaterialTheme.typography.titleMedium)
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
                                        Button(onClick = {
                                            audioRouter.useDeviceAudio()
                                            selectedRoute = "Device audio"
                                        }) { Text("Use device audio") }
                                        Button(onClick = { routes = audioRouter.availableRoutes() }) { Text("Refresh audio routes") }
                                        routes.filter { it.id >= 0 }.forEach { route ->
                                            TextButton(onClick = {
                                                if (audioRouter.useRoute(route.id)) selectedRoute = route.name
                                            }) { Text("Use ${route.name}") }
                                        }
                                        Button(onClick = { alertPulse = true }) { Text("Preview JARVIS alert") }
                                    }
                                }
                            }
                            items(state.devices) { d ->
                                ElevatedCard(onClick = { ble.connect(d.address) }, colors = CardDefaults.elevatedCardColors(containerColor = JarvisPanel)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(d.name, fontWeight = FontWeight.SemiBold)
                                        Text(d.address, color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusTile(label: String, value: String, modifier: Modifier = Modifier) {
        Card(modifier, colors = CardDefaults.cardColors(containerColor = JarvisPanel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(label, color = JarvisBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(value, color = JarvisText, style = MaterialTheme.typography.bodyMedium)
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
                }
                delay(30_000)
            }
        }
    }
}
