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

private data class BoardMember(val name:String,val role:String,val briefing:String,val color:Color,@DrawableRes val portrait:Int)
private val boardMembers=listOf(
 BoardMember("Athena","Chief Strategy Officer","Chairman, strategy is aligned. Focus resources on reliable JARVIS operations, customer value, and measurable revenue.",Color(0xFF53C8FF),R.drawable.director_0),
 BoardMember("Marcus","Chief Legal Officer","Chairman, legal review is active. High impact actions should remain documented, authorized, and compliant.",Color(0xFF8A7DFF),R.drawable.director_1),
 BoardMember("Elena","Chief Financial Officer","Chairman, financial controls are active. Protect cash flow and track return on every initiative.",Color(0xFFFFC857),R.drawable.director_2),
 BoardMember("Nova","Chief Technology Officer","Chairman, the technology priority is stability, watch connectivity, voice, and security.",Color(0xFF37E6B0),R.drawable.director_3),
 BoardMember("Maya","Chief Growth Officer","Chairman, growth should center on a clear offer, fast follow up, and proof of customer value.",Color(0xFFFF6FAE),R.drawable.director_4),
 BoardMember("Victor","Chief Risk Officer","Chairman, risk controls are online. I will flag security, privacy, health, legal, and financial exposure.",Color(0xFFFF7657),R.drawable.director_5))

@Composable fun BoardMeetingPanel(onSpeak:(String)->Unit,modifier:Modifier=Modifier){
 var active by remember{mutableIntStateOf(0)};var speaking by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();val current=boardMembers[active]
 Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF101826))){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("EXECUTIVE BOARD MEETING • HUMAN AVATARS",color=Color(0xFF59C9FF),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
  Text("Chairman Jerome Office • 6 AI directors",color=Color(0xFFA8BDD0))
  TalkingPortrait(current,speaking,Modifier.fillMaxWidth().height(310.dp))
  Text("${current.name} • ${current.role}",color=Color.White,fontWeight=FontWeight.Bold)
  boardMembers.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){row.forEach{m->val i=boardMembers.indexOf(m);DirectorCard(m,active==i,Modifier.weight(1f)){active=i;speaking=true;onSpeak(m.briefing);scope.launch{delay(7000);speaking=false}}}}}
  Button(onClick={scope.launch{for((i,m)in boardMembers.withIndex()){active=i;speaking=true;onSpeak(m.briefing);delay((m.briefing.length*48L).coerceIn(5000L,12000L))};speaking=false}},modifier=Modifier.fillMaxWidth()){Text("START SPOKEN BOARD MEETING",color=Color(0xFF07101B),fontWeight=FontWeight.Bold)}
 }}}

@Composable private fun TalkingPortrait(m:BoardMember,speaking:Boolean,modifier:Modifier=Modifier){val transition=rememberInfiniteTransition(label="portrait");val pulse by transition.animateFloat(1f,if(speaking)1.025f else 1.008f,infiniteRepeatable(tween(if(speaking)380 else 1600),RepeatMode.Reverse),label="speechPulse")
 val sway by transition.animateFloat(-1.5f,1.5f,infiniteRepeatable(tween(if(speaking)650 else 1900),RepeatMode.Reverse),label="headSway")
 val nod by transition.animateFloat(-4f,4f,infiniteRepeatable(tween(if(speaking)420 else 1500),RepeatMode.Reverse),label="headNod")
 val mouth by transition.animateFloat(5f,if(speaking)20f else 7f,infiniteRepeatable(tween(if(speaking)150 else 1200),RepeatMode.Reverse),label="mouth")
 val blink by transition.animateFloat(0f,1f,infiniteRepeatable(keyframes{durationMillis=3200;0f at 0;0f at 2750;1f at 2860;0f at 3000}),label="blink")
 Box(modifier.clip(RoundedCornerShape(20.dp)).border(2.dp,m.color,RoundedCornerShape(20.dp)).background(Color(0xFF07101B)),contentAlignment=Alignment.Center){
  Image(painterResource(m.portrait),m.name,Modifier.fillMaxSize().graphicsLayer{scaleX=pulse;scaleY=pulse;rotationZ=sway;translationY=nod},contentScale=ContentScale.Crop)
  if(blink>.55f){Row(Modifier.align(Alignment.Center).offset(y=(-45).dp),horizontalArrangement=Arrangement.spacedBy(42.dp)){repeat(2){Box(Modifier.width(42.dp).height(4.dp).background(Color(0xDD151018),CircleShape))}}}
  Box(Modifier.align(Alignment.Center).offset(y=55.dp).width(48.dp).height(mouth.dp).background(Color(0xDD250D16),CircleShape).border(1.dp,m.color.copy(alpha=.7f),CircleShape))
  if(speaking){Row(Modifier.align(Alignment.BottomCenter).padding(bottom=44.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){repeat(9){i->val h=(8+(i%4)*5).dp;Box(Modifier.width(3.dp).height(h).background(m.color,CircleShape))}}}
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent,Color(0xCC07101B)))))
  Text(if(speaking)"SPEAKING • LIVE MOTION" else "READY • BLINKING",color=m.color,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp))
 }}

@Composable private fun DirectorCard(m:BoardMember,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=if(selected)m.color.copy(alpha=.2f)else Color(0xFF162234)),shape=RoundedCornerShape(18.dp),border=BorderStroke(if(selected)2.dp else 1.dp,if(selected)m.color else Color(0xFF29405A))){Column(Modifier.fillMaxWidth().padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){Image(painterResource(m.portrait),m.name,Modifier.size(94.dp).clip(CircleShape).border(3.dp,m.color,CircleShape),contentScale=ContentScale.Crop);Spacer(Modifier.height(7.dp));Text(m.name,color=Color.White,fontWeight=FontWeight.Bold);Text(m.role,color=Color(0xFFA8BDD0),style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center)}}}
