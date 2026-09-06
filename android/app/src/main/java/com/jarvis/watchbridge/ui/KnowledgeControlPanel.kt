package com.jarvis.watchbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.watchbridge.knowledge.KnowledgeLearningScheduler
import com.jarvis.watchbridge.knowledge.KnowledgeRepository
import kotlinx.coroutines.launch

@Composable
fun KnowledgeControlPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { KnowledgeRepository() }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(KnowledgeLearningScheduler.isEnabled(context)) }
    var status by remember { mutableStateOf("Knowledge database ready") }
    var stats by remember { mutableStateOf("Stats not loaded") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101826))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("JARVIS INTERNET BRAIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Cloud knowledge database • vector search • robots-aware learning", color = Color(0xFFA8BDD0))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Continuous Learning", fontWeight = FontWeight.SemiBold)
                    Text("Processes a small queued batch about once per hour when network and battery conditions allow.", color = Color(0xFFA8BDD0), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        if (checked) KnowledgeLearningScheduler.enable(context) else KnowledgeLearningScheduler.disable(context)
                        status = if (checked) "Continuous learning enabled" else "Continuous learning disabled"
                    }
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        KnowledgeLearningScheduler.runOnce(context)
                        status = "Learning batch scheduled"
                    }
                ) { Text("Learn Now") }

                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            stats = runCatching {
                                val s = repo.stats()
                                "${s.sources} sources • ${s.chunks} chunks • ${s.queued} queued"
                            }.getOrElse { "Stats unavailable: ${it.message ?: "unknown error"}" }
                            busy = false
                        }
                    }
                ) { Text("Database Stats") }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Teach JARVIS a public URL") },
                placeholder = { Text("https://example.org/article") }
            )
            Button(
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = url.trim()
                    busy = true
                    scope.launch {
                        status = runCatching { repo.learnUrl(target) }
                            .getOrElse { "Learning failed: ${it.message ?: "unknown error"}" }
                        busy = false
                    }
                }
            ) { Text("Learn This Source") }

            Text(stats, color = Color(0xFF59C9FF), fontWeight = FontWeight.SemiBold)
            Text(status, color = Color(0xFFF3F8FF))
            Text(
                "JARVIS will not crawl a source when robots.txt blocks it. Private/local network targets are rejected by the backend.",
                color = Color(0xFFA8BDD0),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
