package com.ferforastieri.valky.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.ferforastieri.valky.R

/**
 * Camera widget: shows the latest snapshot of the chosen camera and refreshes it
 * in the background. Tapping the picture opens the live view in the app.
 */
class CameraWidgetProvider : ValkyWidgetProvider() {
    private companion object {
        const val STALE_AFTER = 5 * 60 * 1000L
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Chamado pelo launcher a qualquer momento: nenhuma falha aqui pode
        // derrubar o processo, no pior caso o widget fica desatualizado.
        runCatching {
            val snapshot = WidgetBridge.state(context)
            // The home screen can ask for a redraw long after the last alarm: keep
            // the cached state fresh without waiting for the next scheduled refresh.
            if (System.currentTimeMillis() - snapshot.updatedAt > STALE_AFTER) requestRefresh(context)
            val photo = WidgetBridge.photo(context)
            ids.forEach { id -> manager.updateAppWidget(id, views(context, snapshot, photo)) }
        }.onFailure { Log.w("ValkyWidget", "Camera widget update failed: ${it.javaClass.simpleName}") }
    }

    private fun views(context: Context, snapshot: WidgetSnapshot, photo: android.graphics.Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_camera)
        val camera = snapshot.camera
        views.setTextViewText(R.id.widget_camera_name, camera?.name ?: context.getString(R.string.widget_camera_title))
        views.setOnClickPendingIntent(R.id.widget_camera_refresh, pendingRefresh(context, 21))
        views.setOnClickPendingIntent(
            R.id.widget_camera_image,
            if (camera != null) pendingOpenApp(context, "camera:${camera.id}", 22) else pendingOpenApp(context, "devices", 23),
        )
        if (photo != null) {
            views.setImageViewBitmap(R.id.widget_camera_image, photo)
        } else {
            views.setImageViewResource(R.id.widget_camera_image, R.drawable.widget_camera)
        }
        val updated = WidgetBridge.photoTimestamp(context).takeIf { it > 0 }
        views.setTextViewText(
            R.id.widget_camera_updated,
            when {
                !WidgetBridge.signedIn(context) -> context.getString(R.string.widget_signed_out)
                camera == null -> context.getString(R.string.widget_camera_empty)
                snapshot.message.isNotBlank() -> snapshot.message
                updated != null -> android.text.format.DateFormat.format("HH:mm", updated).toString()
                else -> context.getString(R.string.widget_camera_waiting)
            },
        )
        views.setViewVisibility(R.id.widget_camera_updated, View.VISIBLE)
        return views
    }
}
