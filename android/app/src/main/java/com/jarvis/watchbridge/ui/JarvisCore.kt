package com.jarvis.watchbridge.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class JarvisVisualState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ALERT
}

@Composable
fun JarvisCore(
    state: JarvisVisualState,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "jarvis-core")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2400)),
        label = "sweep"
    )
    val wave by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = minOf(size.width, size.height) * 0.27f
            val activity = when (state) {
                JarvisVisualState.IDLE -> 0.65f
                JarvisVisualState.LISTENING -> 0.9f
                JarvisVisualState.THINKING -> 1f
                JarvisVisualState.SPEAKING -> 0.95f
                JarvisVisualState.ALERT -> 1f
            }

            drawCircle(
                color = primary.copy(alpha = 0.10f),
                radius = baseRadius * 1.55f * pulse,
                center = center
            )
            drawCircle(
                color = primary.copy(alpha = 0.28f),
                radius = baseRadius * 1.15f,
                center = center,
                style = Stroke(width = 3.5f)
            )
            drawArc(
                color = secondary.copy(alpha = 0.95f),
                startAngle = sweep,
                sweepAngle = 118f,
                useCenter = false,
                topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                size = Size(baseRadius * 2f, baseRadius * 2f),
                style = Stroke(width = 7f, cap = StrokeCap.Round)
            )
            drawArc(
                color = tertiary.copy(alpha = 0.75f),
                startAngle = -sweep * 0.72f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(center.x - baseRadius * 0.72f, center.y - baseRadius * 0.72f),
                size = Size(baseRadius * 1.44f, baseRadius * 1.44f),
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )
            drawCircle(
                color = primary.copy(alpha = 0.9f),
                radius = baseRadius * 0.33f * pulse,
                center = center
            )

            if (state == JarvisVisualState.LISTENING || state == JarvisVisualState.SPEAKING || state == JarvisVisualState.ALERT) {
                val bars = 9
                val gap = 9f
                val totalWidth = (bars - 1) * gap
                val startX = center.x - totalWidth / 2f
                repeat(bars) { index ->
                    val distanceFromCenter = kotlin.math.abs(index - bars / 2).toFloat()
                    val taper = 1f - (distanceFromCenter / (bars / 2f + 1f)) * 0.55f
                    val height = baseRadius * 0.42f * wave * taper * activity
                    val x = startX + index * gap
                    drawLine(
                        color = secondary.copy(alpha = 0.9f),
                        start = Offset(x, center.y - height / 2f),
                        end = Offset(x, center.y + height / 2f),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round
                    )
                }
            }

            if (state == JarvisVisualState.ALERT) {
                drawCircle(
                    color = tertiary.copy(alpha = 0.5f),
                    radius = baseRadius * 1.38f * pulse,
                    center = center,
                    style = Stroke(width = 5f)
                )
            }
        }
    }
}
