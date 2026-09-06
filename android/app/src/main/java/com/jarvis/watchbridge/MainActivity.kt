package com.jarvis.watchbridge

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import com.jarvis.watchbridge.audio.AudioRouter
import com.jarvis.watchbridge.ble.BleManager
import com.jarvis.watchbridge.control.JarvisRemoteCommandService
import com.jarvis.watchbridge.device.DeviceRoleManager
import com.jarvis.watchbridge.health.HealthRepository
import com.jarvis.watchbridge.mood.MoodEngine
import com.jarvis.watchbridge.notifications.NotificationHelper
import com.jarvis.watchbridge.notifications.PhoneMessageRepository
import com.jarvis.watchbridge.security.DeviceSecurityScanner
import com.jarvis.watchbridge.ui.JarvisPortrait
import com.jarvis.watchbridge.ui.JarvisVisualState
import com.jarvis.watchbridge.voice.AlwaysListeningService
import com.jarvis.watchbridge.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
    private lateinit var deviceRoles: DeviceRoleManager
    private lateinit var moodEngine: MoodEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(this)
        health = HealthRepository(this)
        notifications = NotificationHelper(this)
        audioRouter = AudioRouter(this)
        speech = SpeechOutput(this)
        deviceRoles = DeviceRoleManager(this)
        moodEngine = MoodEngine(this)
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
                var deviceRole by remember { mutableStateOf(deviceRoles.preferredRole) }
                var primaryDeviceId by remember { mutableStateOf<String?>(null) }
                var roleMessage by remember { mutableStateOf<String?>(null) }
                var jarvisVolume by remember { mutableIntStateOf(100) }
                var adaptiveMoodLabel by remember {
                    mutableStateOf(if (moodEngine.isAdaptiveEnabled()) "Neutral" else "Off")
                }
                var remoteControlEnabled by remember { mutableStateOf(false) }
                var showAccessibilityDisclosure by remember { mutableStateOf(false) }
                var securitySummary by remember { mutableStateOf("Security inventory not run") }

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

                LaunchedEffect(Unit) {
                    try {
                        val registered = deviceRoles.register(
                            connected = state.connectedName != null,
                            watchBleAddress = state.connectedAddress
                        )
                        deviceRole = registered.role
                        primaryDeviceId = registered.primaryDeviceId
                    } catch (e: Exception) {
                        deviceRole = deviceRoles.preferredRole
                        roleMessage = "Cloud device-role sync unavailable: ${e.message ?: "offline"}"
                    }
                }

                LaunchedEffect(state.connectedAddress, deviceRole) {
                    while (isActive) {
                        delay(60_000)
                        try {
                            val heartbeat = deviceRoles.heartbeat(
                                connected = state.connectedName != null,
                                watchBleAddress = state.connectedAddress
                            )
                            deviceRole = heartbeat.role
                        } catch (_: Exception) {
                            // Local watch features remain available if cloud role sync is offline.
                        }
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val denied = grants.filterValues { !it }.keys
                    roleMessage = if (denied.isEmpty()) "Required permissions granted" else "Some capabilities remain permission-limited"
                }

                val healthLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { }

                val speechLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    listening = false
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        val heard = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull()
                            ?.trim()
                        if (!heard.isNullOrBlank()) prompt = heard
                    }
                }

                if (showAccessibilityDisclosure) {
                    AlertDialog(
                        onDismissRequest = { showAccessibilityDisclosure = false },
                        title = { Text("Accessibility control disclosure") },
                        text = {
                            Text(
                                "JARVIS can use Android Accessibility only after you enable it. " +
                                    "It may read visible interface text and perform deterministic actions you explicitly approve, " +
                                    "such as Back, Home, tapping a named control, scrolling, or entering non-password text. " +
                                    "It will not capture passwords, bypass screen locks, or silently uninstall apps."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showAccessibilityDisclosure = false
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("I understand — open settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("Cancel") }
                        }
                    )
                }

                Surface(Modifier.fillMaxSize(), color = Color(0xFF070B12)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text("JARVIS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = JarvisText)
                            Text("CHAIRMAN COMMAND CENTER • v${BuildConfig.VERSION_NAME}", color = JarvisBlue, style = MaterialTheme.typography.labelLarge)
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
                                StatusTile("ROLE", deviceRole.uppercase(Locale.US), Modifier.weight(1f))
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
                                            onClick = {
                                                listening = true
                                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
                                                }
                                                runCatching { speechLauncher.launch(intent) }
                                                    .onFailure {
                                                        listening = false
                                                        reply = "Speech recognition is unavailable on this device."
                                                    }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(16.dp)
                                        ) { Text(if (listening) "Listening…" else "Listen") }

                                        Button(
                                            enabled = !busy && prompt.isNotBlank(),
                                            onClick = {
                                                val msg = prompt.trim()
                                                busy = true
                                                lifecycleScope.launch {
                                                    val localControl = moodEngine.handleControlCommand(msg)
                                                    if (localControl != null) {
                                                        reply = localControl
                                                        adaptiveMoodLabel = if (moodEngine.isAdaptiveEnabled()) "Neutral" else "Off"
                                                        speech.setVoiceStyle(1.0f, 1.0f)
                                                    } else {
                                                        val mood = moodEngine.observe(msg)
                                                        adaptiveMoodLabel = if (moodEngine.isAdaptiveEnabled()) mood.label.displayName else "Off"
                                                        val adaptiveContext = listOf(healthText, moodEngine.responseDirective(mood))
                                                            .filter { it.isNotBlank() }
                                                            .joinToString("\n\n")
                                                        reply = try {
                                                            chat.send(msg, adaptiveContext)
                                                        } catch (e: Exception) {
                                                            "I hit a connection problem: ${e.message ?: "unknown error"}"
                                                        }
                                                        speech.setVoiceStyle(mood.speechRate, mood.speechPitch)
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
                                    Text("Live status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(state.heartRateBpm?.let { "Heart rate: $it bpm" } ?: "Heart rate: waiting for compatible source", color = JarvisMuted)
                                    Text("BLE services: ${state.services.size} • characteristics: ${state.characteristics.size}", color = JarvisMuted)
                                    Text("Audio: $selectedRoute • JARVIS volume: $jarvisVolume%", color = JarvisMuted)
                                    Text("Adaptive tone: $adaptiveMoodLabel", color = JarvisMuted)
                                    Text("Phone receptionist sync: active", color = JarvisMuted)
                                    Text("Wake service: ${if (alwaysListening) "active" else "off"}", color = JarvisMuted)
                                    Text("Remote control: ${if (remoteControlEnabled) "active" else "off"}", color = JarvisMuted)
                                    Text(securitySummary, color = JarvisMuted)
                                    primaryDeviceId?.let { Text("Primary device: $it", color = JarvisMuted) }
                                    roleMessage?.let { Text(it, color = JarvisMuted) }
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
                                        Text("Health readings are wellness information and are not a medical diagnosis.", color = JarvisMuted)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                enabled = health.isAvailable(),
                                                onClick = { healthLauncher.launch(health.permissions) }
                                            ) { Text("Grant access") }
                                            Button(onClick = {
                                                lifecycleScope.launch {
                                                    healthText = try {
                                                        if (!health.isAvailable()) "Health Connect is unavailable on this device"
                                                        else if (health.hasPermissions()) health.snapshot()
                                                        else "Grant Health Connect permissions first"
                                                    } catch (e: Exception) {
                                                        "Health unavailable: ${e.message ?: "unknown error"}"
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
                            ) { Text(if (showSystems) "Hide system controls" else "Watch, device & security controls") }
                        }

                        if (showSystems) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = JarvisPanel),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Watch connection", style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = {
                                            permissionLauncher.launch(arrayOf(
                                                Manifest.permission.RECORD_AUDIO,
                                                Manifest.permission.BLUETOOTH_SCAN,
                                                Manifest.permission.BLUETOOTH_CONNECT,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                                Manifest.permission.ACTIVITY_RECOGNITION,
                                                Manifest.permission.CAMERA
                                            ))
                                        }, modifier = Modifier.fillMaxWidth()) { Text("Grant required Android permissions") }

                                        Button(
                                            enabled = deviceRole == "primary",
                                            onClick = { ble.connectTargetWatch() },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Connect JARVIS target watch") }

                                        Button(
                                            enabled = deviceRole == "primary",
                                            onClick = { if (state.scanning) ble.stopScan() else ble.startScan() },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(if (state.scanning) "Stop watch scan" else "Scan for BLE watches") }

                                        if (deviceRole != "primary") {
                                            Button(
                                                onClick = {
                                                    lifecycleScope.launch {
                                                        try {
                                                            val takeover = deviceRoles.takeOver(state.connectedAddress)
                                                            deviceRole = takeover.role
                                                            primaryDeviceId = takeover.primaryDeviceId
                                                            roleMessage = "This device is now the primary watch host"
                                                            ble.connectTargetWatch()
                                                        } catch (e: Exception) {
                                                            roleMessage = "Watch takeover failed: ${e.message ?: "unknown error"}"
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Take Over Watch Connection") }
                                        }

                                        HorizontalDivider()
                                        Text("Audio", style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = {
                                            audioRouter.useDeviceAudio()
                                            selectedRoute = "Device audio"
                                        }, modifier = Modifier.fillMaxWidth()) { Text("Use device audio") }
                                        Button(onClick = { routes = audioRouter.availableRoutes() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh audio routes") }
                                        Button(onClick = { jarvisVolume = audioRouter.setJarvisVolume(100) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("JARVIS volume: $jarvisVolume% (Max)")
                                        }
                                        routes.filter { it.id >= 0 }.forEach { route ->
                                            TextButton(onClick = {
                                                if (audioRouter.useRoute(route.id)) selectedRoute = route.name
                                            }) { Text("Use ${route.name}") }
                                        }

                                        HorizontalDivider()
                                        Text("Adaptive response style", style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = {
                                            val enabled = !moodEngine.isAdaptiveEnabled()
                                            moodEngine.setAdaptiveEnabled(enabled)
                                            adaptiveMoodLabel = if (enabled) "Neutral" else "Off"
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (moodEngine.isAdaptiveEnabled()) "Disable mood adaptation" else "Enable mood adaptation")
                                        }
                                        Button(onClick = {
                                            moodEngine.resetLearning()
                                            adaptiveMoodLabel = if (moodEngine.isAdaptiveEnabled()) "Neutral" else "Off"
                                        }, modifier = Modifier.fillMaxWidth()) { Text("Reset mood learning") }

                                        HorizontalDivider()
                                        Text("Device control & security", style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = {
                                            if (!remoteControlEnabled) {
                                                JarvisRemoteCommandService.start(this@MainActivity)
                                                remoteControlEnabled = true
                                            } else {
                                                JarvisRemoteCommandService.stop(this@MainActivity)
                                                remoteControlEnabled = false
                                            }
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (remoteControlEnabled) "Disable JARVIS Remote Control" else "Enable JARVIS Remote Control")
                                        }
                                        Button(onClick = { showAccessibilityDisclosure = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Accessibility control permission")
                                        }
                                        Button(onClick = {
                                            val report = DeviceSecurityScanner(this@MainActivity).scan()
                                            securitySummary = "Security inventory: ${report.scannedApps} visible apps • ${report.findings.size} findings"
                                            roleMessage = report.findings.firstOrNull()?.let { "Top finding: ${it.title}" }
                                                ?: "Security inventory found no flagged items in its visible scope"
                                        }, modifier = Modifier.fillMaxWidth()) { Text("Run Device Security Inventory") }
                                        Text(
                                            "Security inventory is heuristic. It identifies permission/install-source signals; it does not claim an app is malware solely because it has powerful permissions.",
                                            color = JarvisMuted,
                                            style = MaterialTheme.typography.bodySmall
                                        )

                                        Button(onClick = { alertPulse = true }, modifier = Modifier.fillMaxWidth()) { Text("Preview JARVIS alert") }
                                    }
                                }
                            }

                            items(state.devices) { d ->
                                ElevatedCard(
                                    onClick = { if (deviceRole == "primary") ble.connect(d.address) },
                                    colors = CardDefaults.elevatedCardColors(containerColor = JarvisPanel)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(if (d.jarvisTarget) "${d.name} — JARVIS TARGET" else d.name, fontWeight = FontWeight.SemiBold)
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
        ble.disconnect()
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
                    // Phone-receptionist sync is non-fatal; retry on the next interval.
                }
                delay(30_000)
            }
        }
    }
}
