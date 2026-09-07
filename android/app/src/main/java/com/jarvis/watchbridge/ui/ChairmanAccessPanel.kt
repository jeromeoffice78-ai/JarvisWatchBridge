package com.jarvis.watchbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.watchbridge.auth.AuthStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChairmanAccessPanel(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var pairingCode by remember { mutableStateOf("") }
    var enrolled by remember { mutableStateOf(AuthStore.isEnrolled()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (enrolled) "Chairman device session active" else "Pair this device to unlock protected JARVIS services")
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101826))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CHAIRMAN ACCESS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (enrolled) "Encrypted per-device session • Android Keystore protected"
                else "No reusable server credential is stored in this APK.",
                color = Color(0xFFA8BDD0)
            )

            if (!enrolled) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Chairman pairing code") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Button(
                    enabled = !busy && pairingCode.trim().length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val code = pairingCode.trim()
                        busy = true
                        scope.launch {
                            status = runCatching {
                                val result = AuthStore.enroll(code)
                                pairingCode = ""
                                enrolled = true
                                "Paired ${result.deviceId} • session expires ${formatEpoch(result.expiresAt)}"
                            }.getOrElse { "Pairing failed: ${it.message ?: "unknown error"}" }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Pairing…" else "Pair Chairman Device") }
            } else {
                val expires = AuthStore.expiresAt()
                Text(
                    if (expires > 0) "Session expires ${formatEpoch(expires)}" else "Encrypted session active",
                    color = Color(0xFF59C9FF),
                    fontWeight = FontWeight.SemiBold
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            busy = true
                            scope.launch {
                                val ok = AuthStore.verifySession()
                                status = if (ok) "Chairman session verified with JARVIS backend" else "Session verification failed — pair again if access is rejected"
                                busy = false
                            }
                        }
                    ) { Text("Verify") }
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            AuthStore.clear()
                            enrolled = false
                            status = "Local Chairman session removed from this device"
                        }
                    ) { Text("Unpair") }
                }
            }

            Text(status, color = Color(0xFFF3F8FF))
        }
    }
}

private fun formatEpoch(epochSeconds: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))
}.getOrDefault("later")
