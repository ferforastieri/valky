package com.ferforastieri.valkyris.core.design

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.osmdroid.views.MapView

@Immutable
data class MapVisualStyle(
    val accent: Int,
    val surface: Int,
    val foreground: Int,
    val outline: Int,
    val dark: Boolean,
)

@Composable
fun currentMapVisualStyle(): MapVisualStyle {
    val colors = MaterialTheme.colorScheme
    return MapVisualStyle(
        accent = colors.secondary.toArgb(),
        surface = colors.surface.toArgb(),
        foreground = colors.onSurface.toArgb(),
        outline = colors.outline.toArgb(),
        dark = colors.surface.luminance() < .5f,
    )
}

fun MapView.applyValkyrisMapStyle(style: MapVisualStyle) {
    mapOverlay.setLoadingBackgroundColor(style.surface)
    mapOverlay.setLoadingLineColor(style.outline)
    val scale = if (style.dark) -.34f else .68f
    val offset = if (style.dark) 105f else 70f
    val red = .213f * scale
    val green = .715f * scale
    val blue = .072f * scale
    mapOverlay.setColorFilter(
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    red, green, blue, 0f, offset,
                    red, green, blue, 0f, offset,
                    red, green, blue, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        ),
    )
    setBackgroundColor(style.surface)
}

fun mapMarkerDrawable(density: Float, style: MapVisualStyle): Drawable = object : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.surface; this.style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = style.accent
        this.style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accent; this.style = Paint.Style.FILL }

    override fun getIntrinsicWidth() = (44 * density).toInt()
    override fun getIntrinsicHeight() = (52 * density).toInt()

    override fun draw(canvas: android.graphics.Canvas) {
        val cx = bounds.exactCenterX()
        val top = bounds.top + 4f * density
        val radius = 16f * density
        val bottom = bounds.bottom - 3f * density
        val pin = Path().apply {
            moveTo(cx, bottom)
            cubicTo(cx - 4f * density, bottom - 9f * density, cx - radius, top + 27f * density, cx - radius, top + radius)
            cubicTo(cx - radius, top + 7f * density, cx - 9f * density, top, cx, top)
            cubicTo(cx + 9f * density, top, cx + radius, top + 7f * density, cx + radius, top + radius)
            cubicTo(cx + radius, top + 27f * density, cx + 4f * density, bottom - 9f * density, cx, bottom)
            close()
        }
        canvas.drawPath(pin, fill)
        canvas.drawPath(pin, stroke)
        canvas.drawCircle(cx, top + radius, 6f * density, center)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
        center.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        center.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun mapColorWithAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)
