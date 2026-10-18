package com.ferforastieri.valky.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * passeando por cima. Cada tela tem o seu desenho aqui embaixo, do tamanho do
 * conteúdo real — nunca uma bolinha solta.
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

/**
 * Cartão igual aos dos dispositivos: círculo à esquerda, nome e situação — e,
 * quando o cartão real tem um controle à direita, o bloco dele também.
 */
@Composable
fun SkeletonCard(trailing: Boolean = false) {
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
            if (trailing) ValkySkeleton(Modifier.padding(start = 12.dp).size(38.dp), RoundedCornerShape(12.dp))
        }
    }
}

/** A lista inteira em espera, com a mesma cara de quando ela chega. */
@Composable
fun SkeletonList(rows: Int = 5, modifier: Modifier = Modifier, horizontal: Dp = 18.dp, trailing: Boolean = false) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) { SkeletonCard(trailing = trailing) }
    }
}

/**
 * Cartão de câmera: a área do vídeo em cima (16:9) e o nome com a situação
 * embaixo — a mesma divisão do cartão real.
 */
@Composable
fun SkeletonCameraCard() {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            ValkySkeleton(Modifier.fillMaxWidth().aspectRatio(16 / 9f), RoundedCornerShape(0.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ValkySkeleton(Modifier.size(44.dp), RoundedCornerShape(13.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonLine(width = 150.dp, height = 15.dp)
                    SkeletonLine(width = 100.dp, height = 11.dp)
                }
            }
        }
    }
}

/** A lista de câmeras em espera. */
@Composable
fun SkeletonCameraList(rows: Int = 3, modifier: Modifier = Modifier, horizontal: Dp = 18.dp) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) { SkeletonCameraCard() }
    }
}

/** A área de mídia de um evento, no mesmo 16:9 em que o vídeo aparece. */
@Composable
fun SkeletonMedia(modifier: Modifier = Modifier) {
    ValkySkeleton(modifier.fillMaxWidth().aspectRatio(16 / 9f), RoundedCornerShape(22.dp))
}

/** As linhas do detalhe de um evento: título, subtítulo e o texto de apoio. */
@Composable
fun SkeletonDetailLines(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonLine(width = 210.dp, height = 21.dp)
        SkeletonLine(width = 150.dp, height = 13.dp)
        Spacer(Modifier.height(6.dp))
        SkeletonLine(height = 12.dp)
        SkeletonLine(width = 250.dp, height = 12.dp)
    }
}

/**
 * O controle remoto: energia e teclado 3x4 à esquerda, direcional e volume à
 * direita, e a barra de navegação embaixo — a mesma divisão da tela real.
 */
@Composable
fun SkeletonKeypad(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ValkySkeleton(Modifier.size(56.dp), CircleShape)
            SkeletonLine(width = 130.dp, height = 16.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { ValkySkeleton(Modifier.weight(1f).height(48.dp), RoundedCornerShape(12.dp)) }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ValkySkeleton(Modifier.fillMaxWidth().aspectRatio(1f), CircleShape)
                repeat(2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { ValkySkeleton(Modifier.weight(1f).height(44.dp), RoundedCornerShape(12.dp)) }
                    }
                }
            }
        }
        ValkySkeleton(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(16.dp))
    }
}

/** A tela da lâmpada: energia, área de cor e brilho. */
@Composable
fun SkeletonLightDetail(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonCard(trailing = true)
        ValkySkeleton(Modifier.fillMaxWidth().height(190.dp), MaterialTheme.shapes.large)
        SkeletonLine(height = 14.dp)
    }
}

/** O cartão de perfil: avatar, nome e identificação. */
@Composable
fun SkeletonProfile(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ValkySkeleton(Modifier.size(64.dp), CircleShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SkeletonLine(width = 160.dp, height = 17.dp)
                    SkeletonLine(width = 110.dp, height = 12.dp)
                }
            }
        }
        SkeletonCard()
        SkeletonCard()
    }
}

/** O convite: o quadrado branco onde o QR Code aparece. */
@Composable
fun SkeletonQr(modifier: Modifier = Modifier) {
    ValkySkeleton(modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.medium)
}

/** Texto de espera sobre uma área que ainda vai receber mídia. */
@Composable
fun SkeletonNotice(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ValkySkeleton(Modifier.fillMaxSize(), RoundedCornerShape(22.dp))
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
            Text(
                text,
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
