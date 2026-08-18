package com.noxos.audit.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ContainmentMark(active: Boolean, modifier: Modifier = Modifier, diameter: Dp = 88.dp) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    val infiniteTransition = rememberInfiniteTransition(label = "containment-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "containment-pulse-alpha"
    )
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "containment-rotation"
    )

    Canvas(modifier = modifier.size(diameter)) {
        val strokeWidth = 1.6.dp.toPx()
        val outerRadius = size.minDimension / 2
        drawCircle(color = outline, radius = outerRadius, style = Stroke(strokeWidth))
        rotate(degrees = if (active) rotationAngle else 0f) {
            drawCircle(
                color = accent.copy(alpha = if (active) pulseAlpha else 0.6f),
                radius = outerRadius * 0.6f,
                style = Stroke(
                    width = strokeWidth * 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f))
                )
            )
        }
        drawCircle(
            color = if (active) accent else outline,
            radius = outerRadius * 0.18f
        )
    }
}
