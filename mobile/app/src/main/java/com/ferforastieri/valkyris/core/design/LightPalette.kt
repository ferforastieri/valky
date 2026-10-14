package com.ferforastieri.valkyris.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
