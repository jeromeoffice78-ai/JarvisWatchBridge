package com.jarvis.watchbridge.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.watchbridge.team.TeamRepository
import kotlinx.coroutines.launch

@Composable
fun ExpertTeamPanel(modifier: Modifier = Modifier) {
    val repo = remember { TeamRepository() }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(repo.specialists.first()) }
    var task by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Select an expert, talk directly, or assign work through JARVIS.") }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("READY") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101826))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("JARVIS EXPERT AI TEAM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Talk directly • assign work • coordinate specialists", color = Color(0xFFA8BDD0))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repo.specialists.forEach { specialist ->
                    ExpertCard(
                        specialist = specialist,
                        selected = specialist.id == selected.id,
                        active = busy && specialist.id == selected.id,
                        onClick = { if (!busy) selected = specialist }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF162234))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(selected.name, fontWeight = FontWeight.Bold, color = Color(0xFF59C9FF))
                    Text(selected.title, style = MaterialTheme.typography.bodyMedium)
                    Text(selected.skills.joinToString(" • "), color = Color(0xFFA8BDD0), style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Message or assignment") },
                placeholder = { Text("Example: Marcus, audit the Android build and identify anything blocking production release.") }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy && task.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val message = task.trim()
                        busy = true
                        mode = "TALKING TO ${selected.name.uppercase()}"
                        scope.launch {
                            output = runCatching { repo.chat(selected.id, message) }
                                .getOrElse { "Expert chat failed: ${it.message ?: "unknown error"}" }
                            busy = false
                            mode = "READY"
                        }
                    }
                ) { Text("Talk") }

                Button(
                    enabled = !busy && task.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val instructions = task.trim()
                        busy = true
                        mode = "ASSIGNMENT RUNNING"
                        scope.launch {
                            output = runCatching {
                                val result = repo.assign(
                                    specialistId = selected.id,
                                    title = instructions.lineSequence().firstOrNull()?.take(100) ?: "Chairman assignment",
                                    instructions = instructions
                                )
                                "${selected.name} • ${result.status.uppercase()}\n\n${result.result.ifBlank { "Assignment completed with no text result." }}"
                            }.getOrElse { "Assignment failed: ${it.message ?: "unknown error"}" }
                            busy = false
                            mode = "READY"
                        }
                    }
                ) { Text("Assign") }
            }

            OutlinedButton(
                enabled = !busy && task.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val instructions = task.trim()
                    busy = true
                    mode = "TEAM ORCHESTRATION"
                    scope.launch {
                        val selectedIds = when (selected.id) {
                            "athena_cross" -> listOf("marcus_vale", "orion_blake", "victoria_kane", "athena_cross")
                            else -> listOf(selected.id, "athena_cross")
                        }
                        output = runCatching { repo.orchestrate(instructions, selectedIds) }
                            .getOrElse { "Team orchestration failed: ${it.message ?: "unknown error"}" }
                        busy = false
                        mode = "READY"
                    }
                }
            ) { Text("Assign to JARVIS Team") }

            Text(mode, color = Color(0xFF59C9FF), fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A111C))
            ) {
                Text(output, modifier = Modifier.padding(14.dp), color = Color(0xFFF3F8FF))
            }
        }
    }
}

@Composable
private fun ExpertCard(
    specialist: TeamRepository.Specialist,
    selected: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "expert-${specialist.id}")
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (active) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 700 else 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val glow by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = if (active) 1f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 700 else 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Card(
        modifier = Modifier
            .size(width = 140.dp, height = 170.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF17304A) else Color(0xFF111C2B))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .scale(pulse)
                    .alpha(glow)
                    .background(Color(0xFF59C9FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    specialist.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2),
                    color = Color(0xFF071019),
                    fontWeight = FontWeight.Black
                )
            }
            Text(specialist.name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(specialist.title, style = MaterialTheme.typography.labelSmall, color = Color(0xFFA8BDD0), textAlign = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Text(if (active) "WORKING" else if (selected) "SELECTED" else "AVAILABLE", color = Color(0xFF59C9FF), style = MaterialTheme.typography.labelSmall)
        }
    }
}
