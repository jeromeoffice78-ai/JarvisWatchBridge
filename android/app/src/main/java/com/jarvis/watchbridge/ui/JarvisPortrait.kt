package com.jarvis.watchbridge.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
@Composable fun JarvisPortrait(state:JarvisVisualState,modifier:Modifier=Modifier){val border=when(state){JarvisVisualState.ALERT->Color(0xFFFF4B4B);JarvisVisualState.LISTENING->Color(0xFF8DDCFF);JarvisVisualState.THINKING->Color(0xFF4F9DFF);JarvisVisualState.SPEAKING->Color(0xFF59C9FF);JarvisVisualState.IDLE->Color(0xFF173A57)};Avatar3DView(0,state==JarvisVisualState.SPEAKING,modifier.fillMaxWidth().height(360.dp).background(Color(0xFF08111A),RoundedCornerShape(28.dp)).border(2.dp,border,RoundedCornerShape(28.dp)))}