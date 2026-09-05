package com.jarvis.watchbridge.ui

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun JarvisFace(
    state: JarvisVisualState,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "jarvis-face")

    val breath by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "breath"
    )

    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "blink"
    )

    val mouth by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(240), RepeatMode.Reverse),
        label = "mouth"
    )

    val scan by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "scan"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val alert = Color(0xFFFF4B4B)

    Box(modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val headW = minOf(size.width * 0.42f, 170f) * breath
            val headH = minOf(size.height * 0.66f, 160f) * breath
            val headRect = Rect(cx - headW / 2f, cy - headH / 2f, cx + headW / 2f, cy + headH / 2f)
            val activeColor = if (state == JarvisVisualState.ALERT) alert else primary

            drawOval(
                color = activeColor.copy(alpha = 0.08f),
                topLeft = Offset(headRect.left, headRect.top),
                size = Size(headRect.width, headRect.height)
            )
            drawOval(
                color = activeColor.copy(alpha = 0.75f),
                topLeft = Offset(headRect.left, headRect.top),
                size = Size(headRect.width, headRect.height),
                style = Stroke(width = 3.2f)
            )

            val jaw = Path().apply {
                moveTo(cx - headW * 0.36f, cy + headH * 0.06f)
                lineTo(cx - headW * 0.22f, cy + headH * 0.38f)
                lineTo(cx, cy + headH * 0.47f)
                lineTo(cx + headW * 0.22f, cy + headH * 0.38f)
                lineTo(cx + headW * 0.36f, cy + headH * 0.06f)
            }
            drawPath(jaw, activeColor.copy(alpha = 0.85f), style = Stroke(width = 3.5f, cap = StrokeCap.Round))

            val eyeY = cy - headH * 0.12f
            val eyeDx = headW * 0.20f
            val eyeW = headW * 0.15f
            val eyeH = headH * 0.035f * blink

            fun drawEye(x: Float) {
                drawLine(
                    color = secondary.copy(alpha = 0.95f),
                    start = Offset(x - eyeW, eyeY),
                    end = Offset(x + eyeW, eyeY),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
                drawOval(
                    color = secondary.copy(alpha = 0.9f),
                    topLeft = Offset(x - eyeW * 0.24f, eyeY - eyeH),
                    size = Size(eyeW * 0.48f, eyeH * 2f)
                )
            }
            drawEye(cx - eyeDx)
            drawEye(cx + eyeDx)

            val browTilt = if (state == JarvisVisualState.THINKING) 8f else 0f
            drawLine(activeColor.copy(alpha = 0.8f), Offset(cx - eyeDx - eyeW, eyeY - 18f), Offset(cx - eyeDx + eyeW, eyeY - 15f - browTilt), 3f, StrokeCap.Round)
            drawLine(activeColor.copy(alpha = 0.8f), Offset(cx + eyeDx - eyeW, eyeY - 15f - browTilt), Offset(cx + eyeDx + eyeW, eyeY - 18f), 3f, StrokeCap.Round)

            drawLine(
                color = activeColor.copy(alpha = 0.55f),
                start = Offset(cx, cy - 6f),
                end = Offset(cx - 5f, cy + 30f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )

            val speaking = state == JarvisVisualState.SPEAKING || state == JarvisVisualState.LISTENING
            val mouthOpen = if (speaking) 7f + 10f * mouth else 3.5f
            drawOval(
                color = activeColor.copy(alpha = 0.9f),
                topLeft = Offset(cx - headW * 0.16f, cy + headH * 0.20f - mouthOpen / 2f),
                size = Size(headW * 0.32f, mouthOpen),
                style = Stroke(width = 3f)
            )

            if (state == JarvisVisualState.LISTENING || state == JarvisVisualState.SPEAKING) {
                val bars = 11
                val gap = 10f
                val startX = cx - ((bars - 1) * gap) / 2f
                repeat(bars) { i ->
                    val d = abs(i - bars / 2).toFloat()
                    val taper = 1f - (d / (bars / 2f + 1f)) * 0.55f
                    val h = 10f + 26f * mouth * taper
                    val x = startX + i * gap
                    drawLine(
                        secondary.copy(alpha = 0.75f),
                        Offset(x, cy + headH * 0.58f - h / 2f),
                        Offset(x, cy + headH * 0.58f + h / 2f),
                        3f,
                        StrokeCap.Round
                    )
                }
            }

            if (state == JarvisVisualState.THINKING) {
                val y = cy + scan * headH * 0.34f
                drawLine(
                    color = secondary.copy(alpha = 0.55f),
                    start = Offset(cx - headW * 0.42f, y),
                    end = Offset(cx + headW * 0.42f, y),
                    strokeWidth = 2f
                )
            }

            if (state == JarvisVisualState.ALERT) {
                drawOval(
                    color = alert.copy(alpha = 0.45f),
                    topLeft = Offset(headRect.left - 12f, headRect.top - 12f),
                    size = Size(headRect.width + 24f, headRect.height + 24f),
                    style = Stroke(width = 5f)
                )
            }
        }
    }
}
