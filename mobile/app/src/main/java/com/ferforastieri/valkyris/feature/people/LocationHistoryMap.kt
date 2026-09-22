package com.ferforastieri.valkyris.feature.people

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.ferforastieri.valkyris.core.model.PersonLocation
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.time.Instant

internal fun validHistoryPoint(point: PersonLocation) = point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

// A missing hour is not a recorded journey. Leave gaps between distant samples.
internal fun historyRouteSegments(history: List<PersonLocation>): List<List<PersonLocation>> {
    val points = history.filter(::validHistoryPoint).sortedBy { it.occurredAt }
    val segments = mutableListOf<MutableList<PersonLocation>>()
    points.forEach { point ->
        val previous = segments.lastOrNull()?.lastOrNull()
        val gap = previous?.let {
            runCatching { Instant.parse(point.occurredAt).toEpochMilli() - Instant.parse(it.lastSeenAt.ifBlank { it.occurredAt }).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
        } ?: Long.MAX_VALUE
        if (gap !in 0..30 * 60_000L) segments.add(mutableListOf())
        segments.last().add(point)
    }
    return segments.filter { it.size > 1 }
}

@Composable
internal fun LocationHistoryMap(history: List<PersonLocation>, selected: String?, personColor: String, onSelect: (String) -> Unit) {
    if (LocalInspectionMode.current) { Text("Mapa do histórico"); return }
    val color = parsePersonRouteColor(personColor)
    val selectedColor = color
    val onSelectNow by rememberUpdatedState(onSelect)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true) }
        },
        update = { map ->
            val points = history.filter(::validHistoryPoint)
            map.overlays.clear()
            historyRouteSegments(points).forEach { segment ->
                val density = map.resources.displayMetrics.density
                // A quiet casing separates the person's color from roads and labels.
                listOf(6f to 0xB3FFFFFF.toInt(), 3.25f to color).forEach { (width, stroke) ->
                    map.overlays.add(Polyline(map).apply {
                        setPoints(segment.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = stroke
                        outlinePaint.strokeWidth = width * density
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    })
                }
            }
            points.firstOrNull { it.id == selected }?.let { point ->
                map.overlays.add(Polygon(map).apply {
                    setPoints(Polygon.pointsAsCircle(GeoPoint(point.latitude, point.longitude), point.accuracy.coerceAtLeast(1.0)))
                    fillPaint.color = (selectedColor and 0x00ffffff) or 0x26000000
                    outlinePaint.color = selectedColor
                    outlinePaint.strokeWidth = 2f * map.resources.displayMetrics.density
                })
            }
            points.forEach { point ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(point.latitude, point.longitude)
                    title = point.address.ifBlank { "Localização registrada" }
                    icon = historyPointDrawable(map.resources.displayMetrics.density, point.id == selected, color, selectedColor)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> onSelectNow(point.id); true }
                })
            }
            val frame = points.map { Triple(it.id, it.latitude, it.longitude) } to selected
            if (map.tag != frame && points.isNotEmpty()) {
                map.tag = frame
                map.post {
                    if (map.tag == frame) {
                        val focus = points.firstOrNull { it.id == selected }
                        if (focus != null) { map.controller.setZoom(17.0); map.controller.animateTo(GeoPoint(focus.latitude, focus.longitude)) }
                        else if (points.map { it.latitude to it.longitude }.distinct().size == 1) {
                            map.controller.setZoom(17.0); map.controller.setCenter(GeoPoint(points.first().latitude, points.first().longitude))
                        } else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map { GeoPoint(it.latitude, it.longitude) }), false, (28 * map.resources.displayMetrics.density).toInt(), 17.0, null)
                    }
                }
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

internal fun parsePersonRouteColor(value: String): Int = runCatching {
    val hex = value.removePrefix("#")
    when (hex.length) {
        6 -> (0xFF000000L or hex.toLong(16)).toInt()
        8 -> hex.toLong(16).toInt()
        else -> error("invalid color")
    }
}.getOrDefault(0xFF5B5BD6.toInt())

// The transparent 32dp bounds preserve the touch target around an 8/12dp dot.
internal fun historyPointDrawable(density: Float, selected: Boolean, color: Int, selectedColor: Int): android.graphics.drawable.Drawable =
    object : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = (32 * density).toInt()
        override fun getIntrinsicHeight() = (32 * density).toInt()
        override fun draw(canvas: android.graphics.Canvas) {
            val radius = (if (selected) 6f else 4f) * density
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(x, y, radius + 1.5f * density, paint)
            paint.color = if (selected) selectedColor else color
            canvas.drawCircle(x, y, radius, paint)
            if (selected) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 1.5f * density
                paint.color = android.graphics.Color.argb(180, 32, 36, 32)
                canvas.drawCircle(x, y, radius + 2.5f * density, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
        @Suppress("DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
