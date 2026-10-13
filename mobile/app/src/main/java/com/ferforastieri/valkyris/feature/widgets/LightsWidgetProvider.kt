package com.ferforastieri.valkyris.feature.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ferforastieri.valkyris.R

/**
 * Lights widget: every bulb shows its state and a button that turns it on or
 * off straight from the home screen. The lamp icon pulses in the bulb's current
 * colour while it is on. Commands go to the Valkyris backend, which talks to
 * the bulb over the local network.
 */
class LightsWidgetProvider : ValkyrisWidgetProvider() {
    private companion object {
        const val STALE_AFTER = 5 * 60 * 1000L
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snapshot = WidgetBridge.state(context)
        // The home screen can ask for a redraw long after the last alarm: keep
        // the cached state fresh without waiting for the next scheduled refresh.
        if (System.currentTimeMillis() - snapshot.updatedAt > STALE_AFTER) requestRefresh(context)
        ids.forEach { id -> manager.updateAppWidget(id, views(context, snapshot)) }
    }

    private fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lights)
        views.removeAllViews(R.id.widget_lights_list)
        views.setOnClickPendingIntent(R.id.widget_lights_refresh, pendingRefresh(context, 11))
        views.setOnClickPendingIntent(R.id.widget_lights_root, pendingOpenApp(context, "lights", 12))

        val lights = snapshot.lights
        if (lights.isEmpty()) {
            views.setViewVisibility(R.id.widget_lights_empty, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_lights_empty,
                when {
                    !WidgetBridge.signedIn(context) -> context.getString(R.string.widget_signed_out)
                    snapshot.message.isNotBlank() -> snapshot.message
                    else -> context.getString(R.string.widget_lights_empty)
                },
            )
            return views
        }
        views.setViewVisibility(R.id.widget_lights_empty, View.GONE)
        lights.forEachIndexed { index, light ->
            val row = RemoteViews(context.packageName, R.layout.widget_light_row)
            row.setTextViewText(R.id.widget_light_name, light.name)
            row.setTextViewText(
                R.id.widget_light_state,
                when {
                    !light.online -> context.getString(R.string.widget_light_offline)
                    light.power -> context.getString(R.string.widget_light_on, light.brightness)
                    else -> context.getString(R.string.widget_light_off)
                },
            )
            val on = light.power && light.online
            row.setImageViewResource(R.id.widget_light_bulb, if (on) R.drawable.widget_light_glow else R.drawable.widget_bulb_off)
            row.setInt(R.id.widget_light_bulb, "setColorFilter", if (on) light.color else context.getColor(R.color.widget_muted))
            row.setImageViewResource(R.id.widget_light_power, if (light.power) R.drawable.widget_switch_on else R.drawable.widget_switch_off)
            row.setFloat(R.id.widget_light_power, "setAlpha", if (light.online) 1f else .4f)
            row.setOnClickPendingIntent(R.id.widget_light_power, pendingToggleLight(context, light.id, 100 + index))
            row.setOnClickPendingIntent(R.id.widget_light_row, pendingOpenApp(context, "lights", 120 + index))
            views.addView(R.id.widget_lights_list, row)
        }
        return views
    }
}
