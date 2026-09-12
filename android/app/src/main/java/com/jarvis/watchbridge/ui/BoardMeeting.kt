package com.jarvis.watchbridge.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.watchbridge.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BoardMember(val name:String,val role:String,val briefing:String,val color:Color,@DrawableRes val portrait:Int,val character:Int)
private val boardMembers=listOf(
 BoardMember("Athena","Chief Strategy Officer","Chairman, strategy is aligned. Focus resources on reliable JARVIS operations, customer value, and measurable revenue.",Color(0xFF53C8FF),R.drawable.director_0,1),
 BoardMember("Marcus","Chief Legal Officer","Chairman, legal review is active. High impact actions should remain documented, authorized, and compliant.",Color(0xFF8A7DFF),R.drawable.director_1,2),
 BoardMember("Elena","Chief Financial Officer","Chairman, financial controls are active. Protect cash flow and track return on every initiative.",Color(0xFFFFC857),R.drawable.director_2,3),
 BoardMember("Nova","Chief Technology Officer","Chairman, the technology priority is stability, watch connectivity, voice, and security.",Color(0xFF37E6B0),R.drawable.director_3,4),
 BoardMember("Maya","Chief Growth Officer","Chairman, growth should center on a clear offer, fast follow up, and proof of customer value.",Color(0xFFFF6FAE),R.drawable.director_4,5),
 BoardMember("Victor","Chief Risk Officer","Chairman, risk controls are online. I will flag security, privacy, health, legal, and financial exposure.",Color(0xFFFF7657),R.drawable.director_5,6),
 BoardMember("ARIA","Executive Assistant • Five-Brain AI","Chairman, ARIA is online. Operations, research, technology, finance, and risk intelligence are coordinated and ready for your instructions.",Color(0xFF7BE7FF),R.drawable.director_4,7))

@Composable fun BoardMeetingPanel(onSpeak:(String)->Unit,onAutonomous:suspend()->String,modifier:Modifier=Modifier){
 var active by remember{mutableIntStateOf(0)};var speaking by remember{mutableStateOf(false)};var thinking by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();val current=boardMembers[active]
 Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF101826))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("EXECUTIVE BOARD MEETING • NATIVE 3D",color=Color(0xFF59C9FF),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
  Text("Chairman Jerome Office • 6 AI directors • ARIA",color=Color(0xFFA8BDD0))
  TalkingPortrait(current,speaking,Modifier.fillMaxWidth().height(310.dp))
  Text("${current.name} • ${current.role}",color=Color.White,fontWeight=FontWeight.Bold)
  boardMembers.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){row.forEach{m->val i=boardMembers.indexOf(m);DirectorCard(m,active==i,Modifier.weight(1f)){active=i;speaking=true;onSpeak(m.briefing);scope.launch{delay(7000);speaking=false}}}}}
  Button(enabled=!thinking,onClick={scope.launch{thinking=true;active=0;val result=runCatching{onAutonomous()}.getOrElse{"Autonomous board connection failed: ${it.message ?: "unknown error"}"};thinking=false;speaking=true;onSpeak(result);delay((result.length*35L).coerceIn(7000L,30000L));speaking=false}},modifier=Modifier.fillMaxWidth()){Text(if(thinking)"BOARD AGENTS COLLABORATING…" else "RUN AUTONOMOUS BOARD",color=Color(0xFF07101B),fontWeight=FontWeight.Bold)}
 }}}

@Composable private fun TalkingPortrait(m:BoardMember,speaking:Boolean,modifier:Modifier=Modifier){
 Box(modifier.clip(RoundedCornerShape(20.dp)).border(2.dp,m.color,RoundedCornerShape(20.dp)).background(Color(0xFF07101B)),contentAlignment=Alignment.Center){
  Avatar3DView(m.character,speaking,Modifier.fillMaxSize())
  Text(if(speaking)"SPEAKING • 3D LIVE" else "READY • DRAG TO ROTATE",color=m.color,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp))
 }}

@Composable private fun DirectorCard(m:BoardMember,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=if(selected)m.color.copy(alpha=.2f)else Color(0xFF162234)),shape=RoundedCornerShape(18.dp),border=BorderStroke(if(selected)2.dp else 1.dp,if(selected)m.color else Color(0xFF29405A))){Column(Modifier.fillMaxWidth().padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){Image(painterResource(m.portrait),m.name,Modifier.size(94.dp).clip(CircleShape).border(3.dp,m.color,CircleShape),contentScale=ContentScale.Crop);Spacer(Modifier.height(7.dp));Text(m.name,color=Color.White,fontWeight=FontWeight.Bold);Text(m.role,color=Color(0xFFA8BDD0),style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center)}}}
