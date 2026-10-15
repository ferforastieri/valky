package com.ferforastieri.valkyris.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.LightbulbOff
import com.composables.icons.lucide.Lucide

/**
 * Ícone da lâmpada no traço do projeto, com vida: acesa ele respira, solta um
 * halo na cor atual e troca de cor suavemente; apagada fica neutro e menor.
 */
@Composable
fun AnimatedBulbIcon(
    on: Boolean,
    color: Color,
    offTint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    contentDescription: String? = null,
) {
    val tint by animateColorAsState(
        targetValue = if (on) color else offTint,
        animationSpec = tween(durationMillis = 450),
        label = "bulbTint",
    )
    val transition = rememberInfiniteTransition(label = "bulb")
    val breathe by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.09f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val glow by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.44f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val restScale by animateFloatAsState(
        targetValue = if (on) 1f else 0.90f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "restScale",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (on) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = glow), color.copy(alpha = glow * 0.25f), Color.Transparent),
                    ),
                    radius = this.size.minDimension / 2f,
                )
            }
        }
        Icon(
            if (on) Lucide.Lightbulb else Lucide.LightbulbOff,
            contentDescription,
            Modifier.fillMaxSize().scale(if (on) breathe else restScale),
            tint = tint,
        )
    }
}
