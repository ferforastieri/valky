package com.ferforastieri.valky.feature.widgets

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ferforastieri.valky.R

/**
 * Lista de lâmpadas do widget. É uma coleção de verdade: a lista rola, então
 * aparecem TODAS as lâmpadas, não só as que caberiam na altura do widget.
 *
 * Cada linha traz dois toques, iguais aos do aplicativo: a lâmpada acende ou
 * apaga e o resto da linha abre a tela da luz. Numa coleção o toque passa pelo
 * modelo do widget (o "fill-in" é combinado com o PendingIntent do conjunto),
 * por isso os dois alvos ficam gravados como extras.
 */
class LightsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = LightsFactory(applicationContext)
}

private class LightsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var lights: List<WidgetLight> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        lights = WidgetBridge.state(context).lights
    }

    override fun onDestroy() {
        lights = emptyList()
    }

    override fun getCount(): Int = lights.size

    override fun getViewAt(position: Int): RemoteViews? {
        val light = lights.getOrNull(position) ?: return null
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
        // Igual à lista do app: acesa fica na cor da luz com a aura em volta.
        val on = light.power && light.online
        row.setImageViewResource(R.id.widget_light_bulb, if (on) R.drawable.widget_bulb_on else R.drawable.widget_bulb_off)
        row.setInt(
            R.id.widget_light_bulb,
            "setColorFilter",
            if (on) light.color else context.getColor(R.color.widget_on_secondary_container),
        )
        row.setViewVisibility(R.id.widget_light_glow, if (on) View.VISIBLE else View.GONE)
        if (on) row.setInt(R.id.widget_light_glow, "setColorFilter", light.color)
        row.setTextColor(
            R.id.widget_light_state,
            if (light.online) context.getColor(R.color.widget_muted) else context.getColor(R.color.widget_alarm),
        )
        row.setOnClickFillInIntent(R.id.widget_light_toggle, item(WidgetActionReceiver.ITEM_TOGGLE, light.id))
        row.setOnClickFillInIntent(R.id.widget_light_row, item(WidgetActionReceiver.ITEM_OPEN, light.id))
        return row
    }

    /**
     * Numa coleção o toque é combinado com o PendingIntent do conjunto: os
     * dados vão no endereço (jeito que o Android documenta) e também como
     * extras, para não depender de uma única forma.
     */
    private fun item(action: String, id: String) = Intent()
        .setData("valky://widget/$action/$id".toUri())
        .putExtra(WidgetActionReceiver.EXTRA_ITEM_ACTION, action)
        .putExtra(WidgetActionReceiver.EXTRA_ID, id)

    override fun getViewTypeCount(): Int = 1

    override fun getLoadingView(): RemoteViews? = null

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
