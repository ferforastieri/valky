package com.ferforastieri.valkyris.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

@Composable
fun ValkyrisMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = ColorTokens.BrandTile,
        shadowElevation = 7.dp,
    ) {
        val signal = MaterialTheme.colorScheme.secondary
        val highlight = MaterialTheme.colorScheme.onPrimary
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.minDimension / 64f
            translate(top = -4f * scale) {
            fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
            val shield = Path().apply {
                moveTo(11.5f * scale, 23.5f * scale)
                lineTo(21f * scale, 17.5f * scale)
                lineTo(32f * scale, 23.5f * scale)
                lineTo(43f * scale, 17.5f * scale)
                lineTo(52.5f * scale, 23.5f * scale)
                lineTo(52.5f * scale, 35.75f * scale)
                cubicTo(48.8f * scale, 44.2f * scale, 41.2f * scale, 50.3f * scale, 32f * scale, 54.5f * scale)
                cubicTo(22.8f * scale, 50.3f * scale, 15.2f * scale, 44.2f * scale, 11.5f * scale, 35.75f * scale)
                close()
            }
            drawPath(shield, signal.copy(alpha = .13f))
            drawPath(shield, signal, style = Stroke(3f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val wings = Path().apply {
                moveTo(12f * scale, 24f * scale); lineTo(21f * scale, 31f * scale); lineTo(25f * scale, 21f * scale)
                moveTo(52f * scale, 24f * scale); lineTo(43f * scale, 31f * scale); lineTo(39f * scale, 21f * scale)
            }
            drawPath(wings, signal, style = Stroke(3f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(ColorTokens.BrandTile, 9.5f * scale, point(32f, 35f))
            drawCircle(signal, 9.5f * scale, point(32f, 35f), style = Stroke(3f * scale))
            drawCircle(signal, 3.25f * scale, point(32f, 35f))
            val shine = Path().apply {
                moveTo(28.5f * scale, 31.5f * scale)
                cubicTo(29.7f * scale, 30.5f * scale, 31.2f * scale, 30.1f * scale, 33f * scale, 30.25f * scale)
            }
            drawPath(shine, highlight, style = Stroke(1.75f * scale, cap = StrokeCap.Round))
            }
        }
    }
}
