package com.jarvis.watchbridge.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jarvis.watchbridge.R

@Composable
fun JarvisPortrait(state: JarvisVisualState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "jarvis-articulation")
    val pulse by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(if (state == JarvisVisualState.SPEAKING) 260 else 1200), RepeatMode.Reverse),
        label = "portrait-scale"
    )
    val mouth by transition.animateFloat(
        initialValue = 3f,
        targetValue = if (state == JarvisVisualState.SPEAKING) 15f else 4f,
        animationSpec = infiniteRepeatable(tween(if (state == JarvisVisualState.SPEAKING) 135 else 1000), RepeatMode.Reverse),
        label = "mouth"
    )
    val border = when (state) {
        JarvisVisualState.ALERT -> Color(0xFFFF4B4B)
        JarvisVisualState.LISTENING -> Color(0xFF8DDCFF)
        JarvisVisualState.THINKING -> Color(0xFF4F9DFF)
        JarvisVisualState.SPEAKING -> Color(0xFF59C9FF)
        JarvisVisualState.IDLE -> Color(0xFF173A57)
    }
    Box(
        modifier.fillMaxWidth().height(360.dp).scale(pulse).clip(RoundedCornerShape(28.dp)).border(2.dp, border, RoundedCornerShape(28.dp)).background(Color(0xFF08111A)),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.jarvis_chairman), "Animated JARVIS AI companion", Modifier.fillMaxWidth().height(360.dp), contentScale = ContentScale.Crop)
        if (state == JarvisVisualState.SPEAKING) {
            Canvas(Modifier.fillMaxWidth().height(360.dp)) {
                val y = size.height * .463f
                drawLine(Color(0xFF7BE6FF), Offset(size.width * .46f, y - mouth / 2), Offset(size.width * .54f, y + mouth / 2), strokeWidth = 5f, cap = StrokeCap.Round)
            }
        }
    }
}
