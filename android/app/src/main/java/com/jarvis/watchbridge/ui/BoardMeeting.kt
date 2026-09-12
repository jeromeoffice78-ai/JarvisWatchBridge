package com.jarvis.watchbridge.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
data class BoardMember(val name:String,val role:String,val briefing:String,val color:Color)
private val boardMembers=listOf(
 BoardMember("Athena","Chief Strategy Officer","Chairman, strategy is aligned. Focus resources on reliable Jarvis operations, customer value, and measurable revenue.",Color(0xFF53C8FF)),
 BoardMember("Marcus","Chief Legal Officer","Chairman, legal review is active. High impact actions should remain documented, authorized, and compliant.",Color(0xFF8A7DFF)),
 BoardMember("Elena","Chief Financial Officer","Chairman, financial controls are active. Protect cash flow and track return on every initiative.",Color(0xFFFFC857)),
 BoardMember("Nova","Chief Technology Officer","Chairman, the technology priority is stability, watch connectivity, voice, and security.",Color(0xFF37E6B0)),
 BoardMember("Maya","Chief Growth Officer","Chairman, growth should center on a clear offer, fast follow up, and proof of customer value.",Color(0xFFFF6FAE)),
 BoardMember("Victor","Chief Risk Officer","Chairman, risk controls are online. I will flag security, privacy, health, legal, and financial exposure.",Color(0xFFFF7657)))
@Composable fun BoardMeetingPanel(onSpeak:(String)->Unit,modifier:Modifier=Modifier){var active by remember{mutableIntStateOf(0)};var speaking by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
 Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF101826))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
 Text("EXECUTIVE BOARD MEETING • LIVE 3D",color=Color(0xFF59C9FF),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Chairman Jerome Office • 6 AI directors",color=Color(0xFFA8BDD0))
 Avatar3DView(active+1,speaking,Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF07101B),RoundedCornerShape(20.dp)).border(2.dp,boardMembers[active].color,RoundedCornerShape(20.dp)))
 Text(boardMembers[active].name+" • "+boardMembers[active].role,color=Color.White,fontWeight=FontWeight.Bold)
 boardMembers.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{m->val i=boardMembers.indexOf(m);Button(onClick={active=i;speaking=true;onSpeak(m.briefing)},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=if(active==i)m.color.copy(alpha=.35f) else Color(0xFF162234))){Text(m.name)}}}}
 Button(onClick={scope.launch{for((i,m) in boardMembers.withIndex()){active=i;speaking=true;onSpeak(m.briefing);delay((m.briefing.length*48L).coerceIn(5000L,12000L))};speaking=false}},modifier=Modifier.fillMaxWidth()){Text("START 3D SPOKEN BOARD MEETING")}
 }}}