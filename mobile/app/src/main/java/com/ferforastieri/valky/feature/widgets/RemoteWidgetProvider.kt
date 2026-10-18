package com.ferforastieri.valky.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.ferforastieri.valky.R

/**
 * Remote widget: a compact control for the first registered TV. The keys travel
 * through the Valky backend, which keeps the pairing token.
 */
class RemoteWidgetProvider : ValkyWidgetProvider() {
    private companion object {
        const val STALE_AFTER = 5 * 60 * 1000L
    }

    private data class Key(val view: Int, val key: String)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Chamado pelo launcher a qualquer momento: nenhuma falha aqui pode
        // derrubar o processo, no pior caso o widget fica desatualizado.
        runCatching {
            val snapshot = WidgetBridge.state(context)
            // The home screen can ask for a redraw long after the last alarm: keep
            // the cached state fresh without waiting for the next scheduled refresh.
            if (System.currentTimeMillis() - snapshot.updatedAt > STALE_AFTER) requestRefresh(context)
            ids.forEach { id -> manager.updateAppWidget(id, views(context, snapshot)) }
        }.onFailure { Log.w("ValkyWidget", "Remote widget update failed: ${it.javaClass.simpleName}") }
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
        // Energia igual ao botão do app (desliga ligada, acorda em espera) e o
        // botão ABC troca o teclado aqui mesmo, sem abrir o aplicativo.
        views.setOnClickPendingIntent(R.id.widget_remote_power, pendingRemotePower(context, remote.id, 31))
        views.setOnClickPendingIntent(R.id.widget_remote_name, pendingOpenApp(context, "remote:${remote.id}", 32))
        val letters = WidgetBridge.keypadLetters(context)
        views.setOnClickPendingIntent(R.id.widget_remote_abc, pendingRemoteKeypad(context, 33))
        views.setTextViewText(R.id.widget_remote_abc, context.getString(if (letters) R.string.widget_remote_keypad_letters else R.string.widget_remote_keypad_toggle))
        views.setViewVisibility(R.id.widget_remote_digits, if (letters) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_remote_letters, if (letters) View.VISIBLE else View.GONE)
        val keys = listOf(
            Key(R.id.widget_remote_up, "KEY_UP"),
            Key(R.id.widget_remote_down, "KEY_DOWN"),
            Key(R.id.widget_remote_left, "KEY_LEFT"),
            Key(R.id.widget_remote_right, "KEY_RIGHT"),
            Key(R.id.widget_remote_enter, "KEY_ENTER"),
            Key(R.id.widget_remote_back, "KEY_RETURN"),
            Key(R.id.widget_remote_home, "KEY_HOME"),
            Key(R.id.widget_remote_menu, "KEY_MENU"),
            Key(R.id.widget_remote_channel_up, "KEY_CHUP"),
            Key(R.id.widget_remote_channel_list, "KEY_CH_LIST"),
            Key(R.id.widget_remote_channel_down, "KEY_CHDOWN"),
            Key(R.id.widget_remote_volume_up, "KEY_VOLUP"),
            Key(R.id.widget_remote_volume_down, "KEY_VOLDOWN"),
            Key(R.id.widget_remote_mute, "KEY_MUTE"),
        )
        keys.forEachIndexed { index, key -> views.setOnClickPendingIntent(key.view, pendingRemoteKey(context, key.key, 40 + index)) }
        listOf(
            R.id.widget_remote_digit_1 to "KEY_1",
            R.id.widget_remote_digit_2 to "KEY_2",
            R.id.widget_remote_digit_3 to "KEY_3",
            R.id.widget_remote_digit_4 to "KEY_4",
            R.id.widget_remote_digit_5 to "KEY_5",
            R.id.widget_remote_digit_6 to "KEY_6",
            R.id.widget_remote_digit_7 to "KEY_7",
            R.id.widget_remote_digit_8 to "KEY_8",
            R.id.widget_remote_digit_9 to "KEY_9",
            R.id.widget_remote_digit_0 to "KEY_0",
        ).forEachIndexed { index, (view, key) -> views.setOnClickPendingIntent(view, pendingRemoteKey(context, key, 70 + index)) }
        // Letras, espaço, apagar e enter: os MESMOS textos que o app digita na TV.
        listOf(
            R.id.widget_remote_letter_q to "q",
            R.id.widget_remote_letter_w to "w",
            R.id.widget_remote_letter_e to "e",
            R.id.widget_remote_letter_r to "r",
            R.id.widget_remote_letter_t to "t",
            R.id.widget_remote_letter_y to "y",
            R.id.widget_remote_letter_u to "u",
            R.id.widget_remote_letter_i to "i",
            R.id.widget_remote_letter_o to "o",
            R.id.widget_remote_letter_p to "p",
            R.id.widget_remote_letter_a to "a",
            R.id.widget_remote_letter_s to "s",
            R.id.widget_remote_letter_d to "d",
            R.id.widget_remote_letter_f to "f",
            R.id.widget_remote_letter_g to "g",
            R.id.widget_remote_letter_h to "h",
            R.id.widget_remote_letter_j to "j",
            R.id.widget_remote_letter_k to "k",
            R.id.widget_remote_letter_l to "l",
            R.id.widget_remote_letter_z to "z",
            R.id.widget_remote_letter_x to "x",
            R.id.widget_remote_letter_c to "c",
            R.id.widget_remote_letter_v to "v",
            R.id.widget_remote_letter_b to "b",
            R.id.widget_remote_letter_n to "n",
            R.id.widget_remote_letter_m to "m",
            R.id.widget_remote_text_space to " ",
            R.id.widget_remote_text_backspace to "\b",
            R.id.widget_remote_text_enter to "\n",
        ).forEachIndexed { index, (view, text) -> views.setOnClickPendingIntent(view, pendingRemoteText(context, text, 200 + index)) }
        return views
    }
}
