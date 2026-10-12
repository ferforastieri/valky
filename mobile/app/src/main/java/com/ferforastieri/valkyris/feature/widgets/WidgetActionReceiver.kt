package com.ferforastieri.valkyris.feature.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs the widget actions in the background: refreshing the cached state and
 * sending the commands the user tapped on the home screen.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_REFRESH, ValkyrisWidgetProvider.ACTION_ALARM -> Unit
                    ACTION_TOGGLE_LIGHT -> intent.getStringExtra(EXTRA_ID)?.let { WidgetBridge.toggleLight(app, it) }
                    ACTION_REMOTE_KEY -> intent.getStringExtra(EXTRA_KEY)?.let { WidgetBridge.sendKey(app, it) }
                }
                if (action != ACTION_TOGGLE_LIGHT && action != ACTION_REMOTE_KEY) WidgetBridge.refresh(app)
                refreshWidgetViews(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.ferforastieri.valkyris.widget.REFRESH"
        const val ACTION_TOGGLE_LIGHT = "com.ferforastieri.valkyris.widget.TOGGLE_LIGHT"
        const val ACTION_REMOTE_KEY = "com.ferforastieri.valkyris.widget.REMOTE_KEY"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
    }
}
