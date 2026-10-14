package com.ferforastieri.valkyris.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ícone de lâmpada desenhado (não é um ícone de linha): vidro, base de rosca e
 * brilho quando acesa. A cor do vidro acompanha a cor atual da lâmpada.
 */
@Composable
fun LightBulbIcon(
    on: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    offColor: Color = Color(0xFF6B6F76),
    baseColor: Color = Color(0xFF9AA0A6),
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h * 0.40f
        val glass = w * 0.31f

        if (on) {
            // Halo: a luz vazando do vidro.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = .5f), color.copy(alpha = .12f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = w * 0.52f,
                ),
                radius = w * 0.52f,
                center = Offset(cx, cy),
            )
        }
        // Vidro (bojo).
        drawCircle(color = if (on) color else offColor, radius = glass, center = Offset(cx, cy))
        // Reflexo no vidro.
        drawCircle(
            color = Color.White.copy(alpha = if (on) .38f else .16f),
            radius = glass * 0.30f,
            center = Offset(cx - glass * 0.36f, cy - glass * 0.38f),
        )
        // Base de rosca.
        val baseW = glass * 1.06f
        val baseH = h * 0.155f
        drawRoundRect(
            color = baseColor,
            topLeft = Offset(cx - baseW / 2f, cy + glass * 0.80f),
            size = Size(baseW, baseH),
            cornerRadius = CornerRadius(baseW * 0.28f, baseW * 0.28f),
        )
        // Anéis da rosca.
        repeat(2) { index ->
            val y = cy + glass * 0.80f + baseH * (0.36f + index * 0.34f)
            drawLine(
                color = Color.Black.copy(alpha = .18f),
                start = Offset(cx - baseW / 2f, y),
                end = Offset(cx + baseW / 2f, y),
                strokeWidth = 1.2f,
            )
        }
        if (on) {
            // Filamento aceso.
            val top = cy + glass * 0.30f
            drawLine(color = Color.White.copy(alpha = .75f), start = Offset(cx - glass * 0.28f, top), end = Offset(cx, cy + glass * 0.02f), strokeWidth = 1.6f)
            drawLine(color = Color.White.copy(alpha = .75f), start = Offset(cx + glass * 0.28f, top), end = Offset(cx, cy + glass * 0.02f), strokeWidth = 1.6f)
        }
        // Contorno discreto, para o vidro não "sumir" no fundo claro.
        drawCircle(color = Color.Black.copy(alpha = .10f), radius = glass, center = Offset(cx, cy), style = Stroke(width = 1f))
    }
}

/** Branco da lâmpada: interpola entre âmbar quente (2700 K) e branco frio (6500 K). */
fun warmWhiteColor(kelvin: Int): Color {
    val t = ((kelvin - 2700).coerceIn(0, 3800)) / 3800f
    return Color(
        red = (0xFF / 255f) + (0xDC / 255f - 0xFF / 255f) * t,
        green = (0xD7 / 255f) + (0xE9 / 255f - 0xD7 / 255f) * t,
        blue = (0xA0 / 255f) + (0xFF / 255f - 0xA0 / 255f) * t,
    )
}

/** Cor do ícone que fica legível sobre a cor da lâmpada. */
fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF1B1B1F) else Color.White
