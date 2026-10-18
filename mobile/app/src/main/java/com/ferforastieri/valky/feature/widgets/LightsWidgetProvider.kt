package com.ferforastieri.valky.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.ferforastieri.valky.R

/**
 * Lights widget: every bulb shows its state and can be switched straight from
 * the home screen. The rows are a real list ([LightsWidgetService]), so the
 * widget scrolls through all the lamps. The lamp icon lights up in the bulb's
 * current colour; commands go to the Valky backend, which talks to the bulb
 * over the local network.
 */
class LightsWidgetProvider : ValkyWidgetProvider() {
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
            ids.forEach { id -> manager.updateAppWidget(id, views(context, snapshot)) }
            // Os itens da lista vêm da coleção: avisa que o conjunto mudou.
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_lights_list)
        }.onFailure { Log.w("ValkyWidget", "Lights widget update failed: ${it.javaClass.simpleName}") }
    }

    private fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lights)
        views.setOnClickPendingIntent(R.id.widget_lights_refresh, pendingRefresh(context, 11))
        views.setOnClickPendingIntent(R.id.widget_lights_root, pendingOpenApp(context, "lights", 12))
        views.setRemoteAdapter(R.id.widget_lights_list, Intent(context, LightsWidgetService::class.java))
        views.setPendingIntentTemplate(R.id.widget_lights_list, pendingLightItem(context))
        views.setEmptyView(R.id.widget_lights_list, R.id.widget_lights_empty)
        views.setTextViewText(
            R.id.widget_lights_empty,
            when {
                !WidgetBridge.signedIn(context) -> context.getString(R.string.widget_signed_out)
                snapshot.lights.isNotEmpty() -> ""
                snapshot.message.isNotBlank() -> snapshot.message
                else -> context.getString(R.string.widget_lights_empty)
            },
        )
        return views
    }
}
