package com.jarvis.watchbridge.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.watchbridge.R

@Composable
fun JarvisPortrait(state: JarvisVisualState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "jarvis-portrait")
    val pulse by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = if (state == JarvisVisualState.SPEAKING) 1.035f else 1.01f,
        animationSpec = infiniteRepeatable(
            tween(if (state == JarvisVisualState.SPEAKING) 300 else 1200),
            RepeatMode.Reverse
        ),
        label = "jarvis-motion"
    )
    val border = when (state) {
        JarvisVisualState.ALERT -> Color(0xFFFF4B4B)
        JarvisVisualState.LISTENING -> Color(0xFF8DDCFF)
        JarvisVisualState.THINKING -> Color(0xFF4F9DFF)
        JarvisVisualState.SPEAKING -> Color(0xFF59C9FF)
        JarvisVisualState.IDLE -> Color(0xFF173A57)
    }
    val sway by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "jarvis-sway"
    )
    val shape = RoundedCornerShape(28.dp)
    Avatar3DView(
        character = 0,
        speaking = state == JarvisVisualState.SPEAKING,
        modifier = modifier.fillMaxWidth().height(360.dp).clip(shape)
            .background(Color(0xFF08111A)).border(2.dp, border, shape)
    )
}
