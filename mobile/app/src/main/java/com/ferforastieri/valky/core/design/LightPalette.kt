package com.ferforastieri.valky.core.design

import androidx.compose.ui.graphics.Color

/** Branco da lâmpada: interpola entre âmbar quente (2700 K) e branco frio (6500 K). */
fun warmWhiteColor(kelvin: Int): Color {
    val t = ((kelvin - 2700).coerceIn(0, 3800)) / 3800f
    return Color(
        red = (0xFF / 255f) + (0xDC / 255f - 0xFF / 255f) * t,
        green = (0xD7 / 255f) + (0xE9 / 255f - 0xD7 / 255f) * t,
        blue = (0xA0 / 255f) + (0xFF / 255f - 0xA0 / 255f) * t,
    )
}

/**
 * Cor atual de uma lâmpada: no modo cor, o HSV escolhido; no branco, a
 * temperatura convertida. É o que pinta o ícone e o cabeçalho.
 */
fun lightTint(mode: String, hue: Int, saturation: Int, value: Int, kelvin: Int): Color =
    if (mode == "color") {
        Color.hsv(hue.toFloat(), (saturation / 100f).coerceIn(0f, 1f), (value / 100f).coerceIn(0.12f, 1f))
    } else {
        warmWhiteColor(kelvin)
    }
