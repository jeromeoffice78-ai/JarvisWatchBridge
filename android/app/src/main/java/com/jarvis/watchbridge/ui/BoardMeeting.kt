package com.jarvis.watchbridge.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class BoardMember(val name: String, val role: String, val briefing: String, val color: Color)

private val boardMembers = listOf(
    BoardMember("Athena", "Chief Strategy Officer", "Chairman, strategy is aligned. I recommend focusing resources on reliable Jarvis operations, customer value, and measurable revenue.", Color(0xFF53C8FF)),
    BoardMember("Marcus", "Chief Legal Officer", "Chairman, legal review is active. High impact actions should remain documented, authorized, and compliant before execution.", Color(0xFF8A7DFF)),
    BoardMember("Elena", "Chief Financial Officer", "Chairman, financial controls are active. I recommend protecting cash flow, limiting unnecessary costs, and tracking return on every initiative.", Color(0xFFFFC857)),
    BoardMember("Nova", "Chief Technology Officer", "Chairman, the technology priority is stability. Watch connectivity, voice, security, and honest diagnostics are the current release gates.", Color(0xFF37E6B0)),
    BoardMember("Maya", "Chief Growth Officer", "Chairman, growth should center on a clear offer, fast follow up, repeatable outreach, and proof that the product solves a real customer problem.", Color(0xFFFF6FAE)),
    BoardMember("Victor", "Chief Risk Officer", "Chairman, risk controls are online. I will flag security, privacy, health, legal, and financial exposure before critical decisions.", Color(0xFFFF7657))
)

@Composable
fun BoardMeetingPanel(onSpeak: (String) -> Unit, modifier: Modifier = Modifier) {
    var activeIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101826))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("EXECUTIVE BOARD MEETING", color = Color(0xFF59C9FF), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Chairman Jerome Office • 6 AI directors", color = Color(0xFFA8BDD0))
            boardMembers.chunked(2).forEach { rowMembers ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowMembers.forEach { member ->
                        val index = boardMembers.indexOf(member)
                        BoardMemberCard(member, activeIndex == index, Modifier.weight(1f)) {
                            activeIndex = index
                            onSpeak(member.briefing)
                        }
                    }
                    if (rowMembers.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        boardMembers.forEachIndexed { index, member ->
                            activeIndex = index
                            onSpeak(member.briefing)
                            delay((member.briefing.length * 48L).coerceIn(5_000L, 12_000L))
                        }
                        activeIndex = -1
                    }
                }
            ) { Text("START SPOKEN BOARD MEETING") }
            Text("Tap any director to hear that briefing again.", color = Color(0xFFA8BDD0), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BoardMemberCard(member: BoardMember, speaking: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "board-speaker")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (speaking) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(if (speaking) 260 else 1100), RepeatMode.Reverse),
        label = "speaker-pulse"
    )
    Column(
        modifier.scale(pulse).clickable(onClick = onClick).background(Color(0xFF162234), RoundedCornerShape(18.dp)).border(if (speaking) 2.dp else 1.dp, if (speaking) member.color else Color(0xFF26384D), RoundedCornerShape(18.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TalkingFace(member.color, speaking)
        Spacer(Modifier.height(7.dp))
        Text(member.name, fontWeight = FontWeight.Bold, color = Color.White)
        Text(member.role, color = Color(0xFFA8BDD0), style = MaterialTheme.typography.labelSmall)
        Text(if (speaking) "SPEAKING" else "READY", color = if (speaking) member.color else Color(0xFF698096), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TalkingFace(color: Color, speaking: Boolean) {
    val transition = rememberInfiniteTransition(label = "mouth")
    val mouth by transition.animateFloat(
        initialValue = 2f,
        targetValue = if (speaking) 13f else 3f,
        animationSpec = infiniteRepeatable(tween(if (speaking) 150 else 900), RepeatMode.Reverse),
        label = "mouth-open"
    )
    Box(Modifier.size(76.dp).background(color.copy(alpha = 0.18f), CircleShape).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(58.dp)) {
            drawCircle(color.copy(alpha = 0.25f), radius = size.minDimension / 2)
            drawCircle(Color.White, radius = 3.5f, center = Offset(size.width * .34f, size.height * .40f))
            drawCircle(Color.White, radius = 3.5f, center = Offset(size.width * .66f, size.height * .40f))
            drawLine(color, Offset(size.width * .34f, size.height * .68f - mouth / 2), Offset(size.width * .66f, size.height * .68f + mouth / 2), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}
