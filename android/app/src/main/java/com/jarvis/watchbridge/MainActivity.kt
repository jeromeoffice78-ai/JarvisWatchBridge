package com.jarvis.watchbridge

import android.Manifest
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
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.jarvis.watchbridge.ai.ChatRepository
import com.jarvis.watchbridge.ble.BleManager
import com.jarvis.watchbridge.health.HealthRepository
import com.jarvis.watchbridge.notifications.NotificationHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var ble: BleManager
    private lateinit var health: HealthRepository
    private val chat = ChatRepository()
    private lateinit var notifications: NotificationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleManager(this)
        health = HealthRepository(this)
        notifications = NotificationHelper(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by ble.state.collectAsState()
                var prompt by remember { mutableStateOf("") }
                var reply by remember { mutableStateOf("JARVIS online") }
                var healthText by remember { mutableStateOf("Health data not loaded") }
                var busy by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("JARVIS WATCH BRIDGE", style = MaterialTheme.typography.headlineSmall)
                            Text(state.connectedName?.let { "Connected: $it" } ?: "No watch connected")
                            state.heartRateBpm?.let { Text("Direct BLE heart rate: $it bpm") }
                            state.error?.let { Text(it) }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    permissionLauncher.launch(arrayOf(
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
                            Button(enabled = !busy && prompt.isNotBlank(), onClick = {
                                val msg = prompt
                                busy = true
                                lifecycleScope.launch {
                                    reply = try { chat.send(msg, healthText) } catch (e: Exception) { "Error: ${e.message}" }
                                    notifications.push("JARVIS", reply)
                                    busy = false
                                }
                            }) { Text(if (busy) "Thinking…" else "Send") }
                            Text(reply)
                        }
                        item {
                            Text("If LAXASFIT uses proprietary GATT services, keep its companion app (or a compatible Health Connect bridge) paired and let JARVIS consume normalized health data.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
