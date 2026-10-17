package com.ferforastieri.valky.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Espera de verdade: em vez de um círculo girando, o app desenha a PRÓPRIA
 * forma do conteúdo que vem (cartão, círculo, linha) com um brilho suave
 * passeando por cima. É o que aparece enquanto a lista não chega.
 */
@Composable
private fun shimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    return 0.45f + (progress * 0.35f)
}

@Composable
fun ValkySkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val alpha = shimmerAlpha()
    Surface(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {}
}

/** Uma linha de texto que ainda não chegou. */
@Composable
fun SkeletonLine(width: Dp? = null, height: Dp = 12.dp, shape: Shape = RoundedCornerShape(6.dp)) {
    ValkySkeleton(
        modifier = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).height(height),
        shape = shape,
    )
}

/** Cartão igual aos dos dispositivos: círculo à esquerda, nome e situação. */
@Composable
fun SkeletonCard() {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ValkySkeleton(Modifier.size(48.dp), CircleShape)
            Column(Modifier.weight(1f).padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonLine(height = 15.dp)
                SkeletonLine(width = 110.dp, height = 11.dp)
            }
        }
    }
}

/** A lista inteira em espera, com a mesma cara de quando ela chega. */
@Composable
fun SkeletonList(rows: Int = 5, modifier: Modifier = Modifier, horizontal: Dp = 18.dp) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) { SkeletonCard() }
    }
}
