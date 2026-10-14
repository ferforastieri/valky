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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ícone de lâmpada desenhado: bojo de vidro com brilho, gargalo e base de rosca.
 * Acesa, o vidro toma a cor atual da lâmpada e ganha um halo; apagada, fica um
 * vidro fosco com a base metálica.
 */
@Composable
fun LightBulbIcon(
    on: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val offGlassTop = Color(0xFF555C63)
    val offGlassBottom = Color(0xFF333A40)
    val metalLight = Color(0xFFB9BFC6)
    val metalDark = Color(0xFF8A9096)
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = kotlin.math.min(w, h)
        val cx = w / 2f
        val cy = h * 0.37f
        val r = s * 0.295f
        val baseW = r * 0.80f
        val neckTop = cy + r * 0.70f
        val baseTop = cy + r * 0.86f
        val baseH = s * 0.22f

        if (on) {
            // Halo suave em volta do vidro.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = .45f), color.copy(alpha = .10f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = s * 0.50f,
                ),
                radius = s * 0.50f,
                center = Offset(cx, cy),
            )
        }

        // Gargalo (liga o vidro à base).
        val neck = Path().apply {
            moveTo(cx - r * 0.66f, neckTop)
            lineTo(cx + r * 0.66f, neckTop)
            lineTo(cx + baseW / 2f, baseTop + baseH * 0.2f)
            lineTo(cx - baseW / 2f, baseTop + baseH * 0.2f)
            close()
        }
        drawPath(neck, if (on) metalDark else Color(0xFF6E747A))

        // Base de rosca.
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(metalLight, metalDark)),
            topLeft = Offset(cx - baseW / 2f, baseTop),
            size = Size(baseW, baseH),
            cornerRadius = CornerRadius(baseW * 0.30f, baseW * 0.30f),
        )
        // Anéis da rosca.
        repeat(3) { index ->
            val y = baseTop + baseH * (0.24f + index * 0.26f)
            drawLine(
                color = Color.Black.copy(alpha = .22f),
                start = Offset(cx - baseW / 2f, y),
                end = Offset(cx + baseW / 2f, y),
                strokeWidth = s * 0.018f,
            )
        }

        // Bojo de vidro: gradiente para dar volume.
        val glassBrush = if (on) {
            Brush.radialGradient(
                colors = listOf(lerp(color, Color.White, .45f), color, lerp(color, Color.Black, .18f)),
                center = Offset(cx - r * 0.34f, cy - r * 0.44f),
                radius = r * 1.9f,
            )
        } else {
            Brush.verticalGradient(listOf(offGlassTop, offGlassBottom))
        }
        drawCircle(brush = glassBrush, radius = r, center = Offset(cx, cy))

        // Filamento aceso.
        if (on) {
            val filament = lerp(color, Color.White, .75f)
            val top = cy - r * 0.18f
            drawLine(filament, Offset(cx - r * 0.34f, top), Offset(cx, cy + r * 0.26f), strokeWidth = s * 0.030f)
            drawLine(filament, Offset(cx + r * 0.34f, top), Offset(cx, cy + r * 0.26f), strokeWidth = s * 0.030f)
            drawLine(filament, Offset(cx - r * 0.34f, top), Offset(cx + r * 0.34f, top), strokeWidth = s * 0.030f)
        }

        // Reflexo do vidro.
        drawCircle(
            color = Color.White.copy(alpha = if (on) .40f else .18f),
            radius = r * 0.24f,
            center = Offset(cx - r * 0.36f, cy - r * 0.42f),
        )
        drawCircle(
            color = Color.White.copy(alpha = if (on) .22f else .10f),
            radius = r * 0.12f,
            center = Offset(cx + r * 0.34f, cy - r * 0.06f),
        )
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
