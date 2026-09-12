package com.jarvis.watchbridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
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
import com.jarvis.watchbridge.ai.VisionRepository
import com.jarvis.watchbridge.audio.AudioRouter
import com.jarvis.watchbridge.ble.BleManager
import com.jarvis.watchbridge.health.HealthRepository
import com.jarvis.watchbridge.notifications.NotificationHelper
import com.jarvis.watchbridge.notifications.PhoneMessageRepository
import com.jarvis.watchbridge.ui.BoardMeetingPanel
import com.jarvis.watchbridge.ui.ChairmanCameraPanel
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
    private val vision = VisionRepository()
    private lateinit var notifications: NotificationHelper
    private lateinit var audioRouter: AudioRouter
    private lateinit var speech: SpeechOutput
    private val phoneMessages = PhoneMessageRepository()

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun allOptionalPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        permissions += requiredBlePermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.distinct().toTypedArray()
    }

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
                var showBoard by remember { mutableStateOf(false) }
                var showCamera by remember { mutableStateOf(false) }
                var visionBusy by remember { mutableStateOf(false) }
                var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }

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

                val blePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    val granted = requiredBlePermissions().all { permission ->
                        result[permission] == true || ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            permission
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (granted) ble.connectTargetWatch()
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    val bleGranted = requiredBlePermissions().all { permission ->
                        result[permission] == true || ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            permission
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (bleGranted) ble.connectTargetWatch()
                }

                val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> cameraGranted = granted }

                val speechLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    listening = false
                    if (result.resultCode == RESULT_OK) {
                        val heard = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull().orEmpty()
                        if (heard.isNotBlank()) prompt = heard
                    } else {
                        reply = "I didn't hear a command. Tap Listen and try again."
                    }
                }

                fun startSpeechRecognition() {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
                    }
                    if (intent.resolveActivity(packageManager) == null) {
                        reply = "Speech recognition is unavailable. Use the keyboard to talk to JARVIS."
                    } else {
                        listening = true
                        speechLauncher.launch(intent)
                    }
                }

                val healthLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { }

                LaunchedEffect(Unit) {
                    val granted = requiredBlePermissions().all { permission ->
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            permission
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (granted) {
                        ble.connectTargetWatch()
                    } else {
                        blePermissionLauncher.launch(requiredBlePermissions())
                    }
                }

                Surface(Modifier.fillMaxSize(), color = Color(0xFF070B12)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text("JARVIS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = JarvisText)
                            Text("CHAIRMAN COMMAND CENTER • AUTONOMOUS 3D 3.9", color = JarvisBlue, style = MaterialTheme.typography.labelLarge)
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
                            Button(
                                onClick = { showBoard = !showBoard },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF163B58))
                            ) { Text(if (showBoard) "CLOSE BOARD MEETING" else "OPEN ANIMATED BOARD MEETING") }
                        }

                        if (showBoard) {
                            item {
                                BoardMeetingPanel(onSpeak = { briefing ->
                                    reply = briefing
                                    speech.speak(briefing)
                                }, onAutonomous = { chat.runBoard() })
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { showCamera = !showCamera }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (showCamera) "CLOSE CHAIRMAN CAMERA" else "OPEN CHAIRMAN CAMERA")
                                }
                                if (showCamera) {
                                    Spacer(Modifier.height(10.dp))
                                    ChairmanCameraPanel(
                                        permissionGranted = cameraGranted,
                                        analyzing = visionBusy,
                                        onRequestPermission = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                                        onFrameCaptured = { bytes ->
                                            visionBusy = true
                                            lifecycleScope.launch {
                                                reply = try { vision.describe(bytes) } catch (e: Exception) { "Camera analysis problem: " + (e.message ?: "unknown error") }
                                                visionBusy = false
                                                speech.speak(reply)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        item {
                            val watchStatus = when {
                                state.connectedName != null -> state.connectedName!!
                                state.connectingAddress != null -> "Connecting…"
                                state.scanning -> "Scanning…"
                                else -> "Not connected"
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusTile("WATCH", watchStatus, Modifier.weight(1f))
                                StatusTile("AUDIO", selectedRoute, Modifier.weight(1f))
                            }
                        }

                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { ble.connectTargetWatch() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("Reconnect watch") }
                                Button(
                                    onClick = { if (state.scanning) ble.stopScan() else ble.startScan() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text(if (state.scanning) "Stop scan" else "Scan watches") }
                            }
                        }

                        if (state.devices.isNotEmpty() && state.connectedAddress == null) {
                            item { Text("Tap your watch to connect", color = JarvisBlue, fontWeight = FontWeight.Bold) }
                            items(state.devices) { device ->
                                ElevatedCard(
                                    onClick = { ble.connect(device.address) },
                                    colors = CardDefaults.elevatedCardColors(containerColor = JarvisPanel)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Text(device.name, fontWeight = FontWeight.SemiBold)
                                        Text(device.address, color = JarvisMuted, style = MaterialTheme.typography.bodySmall)
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
                                            onClick = { startSpeechRecognition() },
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
                                                    reply = try {
                                                        chat.send(msg, healthText)
                                                    } catch (e: Exception) {
                                                        "I hit a connection problem: ${e.message ?: "unknown error"}"
                                                    }
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
                                    state.connectedAddress?.let { Text("Watch BLE: $it", color = JarvisMuted) }
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
                                                    } catch (e: Exception) {
                                                        "Health unavailable: ${e.message}"
                                                    }
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
                                            Button(onClick = { permissionLauncher.launch(allOptionalPermissions()) }) {
                                                Text("Permissions")
                                            }
                                            Button(onClick = { ble.connectTargetWatch() }) {
                                                Text("Connect target")
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
                                ElevatedCard(
                                    onClick = { ble.connect(d.address) },
                                    colors = CardDefaults.elevatedCardColors(containerColor = JarvisPanel)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(
                                            if (d.jarvisTarget) "${d.name} — JARVIS TARGET" else d.name,
                                            fontWeight = FontWeight.SemiBold
                                        )
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
        ble.disconnect()
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
