package com.ferforastieri.valkyris.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.ferforastieri.valkyris.R

/**
 * Remote widget: a compact control for the first registered TV. The keys travel
 * through the Valkyris backend, which keeps the pairing token.
 */
class RemoteWidgetProvider : ValkyrisWidgetProvider() {
    private companion object {
        const val STALE_AFTER = 5 * 60 * 1000L
    }

    private data class Key(val view: Int, val key: String)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snapshot = WidgetBridge.state(context)
        // The home screen can ask for a redraw long after the last alarm: keep
        // the cached state fresh without waiting for the next scheduled refresh.
        if (System.currentTimeMillis() - snapshot.updatedAt > STALE_AFTER) requestRefresh(context)
        ids.forEach { id -> manager.updateAppWidget(id, views(context, snapshot)) }
    }

    private fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_remote)
        val remote = snapshot.remotes.firstOrNull()
        views.setTextViewText(R.id.widget_remote_name, remote?.name ?: context.getString(R.string.widget_remote_title))
        val ready = remote != null && WidgetBridge.signedIn(context)
        views.setViewVisibility(R.id.widget_remote_empty, if (ready) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_remote_body, if (ready) View.VISIBLE else View.GONE)
        if (!ready) {
            views.setTextViewText(
                R.id.widget_remote_empty,
                when {
                    !WidgetBridge.signedIn(context) -> context.getString(R.string.widget_signed_out)
                    snapshot.message.isNotBlank() -> snapshot.message
                    else -> context.getString(R.string.widget_remote_empty)
                },
            )
            return views
        }
        views.setOnClickPendingIntent(R.id.widget_remote_power, pendingRemoteKey(context, "KEY_POWER", 31))
        views.setOnClickPendingIntent(R.id.widget_remote_name, pendingOpenApp(context, "remote:${remote!!.id}", 32))
        val keys = listOf(
            Key(R.id.widget_remote_up, "KEY_UP"),
            Key(R.id.widget_remote_down, "KEY_DOWN"),
            Key(R.id.widget_remote_left, "KEY_LEFT"),
            Key(R.id.widget_remote_right, "KEY_RIGHT"),
            Key(R.id.widget_remote_enter, "KEY_ENTER"),
            Key(R.id.widget_remote_volume_up, "KEY_VOLUP"),
            Key(R.id.widget_remote_volume_down, "KEY_VOLDOWN"),
            Key(R.id.widget_remote_mute, "KEY_MUTE"),
        )
        keys.forEachIndexed { index, key -> views.setOnClickPendingIntent(key.view, pendingRemoteKey(context, key.key, 40 + index)) }
        return views
    }
}
