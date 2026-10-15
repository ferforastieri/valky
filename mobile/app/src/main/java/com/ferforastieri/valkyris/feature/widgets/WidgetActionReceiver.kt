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
                    ACTION_TOGGLE_LIGHT -> intent.getStringExtra(EXTRA_ID)?.let { WidgetBridge.toggleLight(app, it) }
                    ACTION_REMOTE_KEY -> intent.getStringExtra(EXTRA_KEY)?.let { WidgetBridge.sendKey(app, it) }
                    // Igual ao botão do app: uma tecla, um texto digitado.
                    ACTION_REMOTE_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { WidgetBridge.sendText(app, it) }
                    // Energia igual à do app: desliga se estiver ligada e acorda
                    // a TV pela rede quando ela estiver em espera.
                    ACTION_REMOTE_POWER -> WidgetBridge.toggleRemotePower(app)
                    // Troca o teclado do widget (números/letras) sem sair da home.
                    ACTION_REMOTE_KEYPAD -> WidgetBridge.setKeypadLetters(app, !WidgetBridge.keypadLetters(app))
                    // Só a imagem da câmera: mantém o widget perto do tempo real
                    // sem repetir todas as chamadas do estado.
                    ACTION_PHOTO_ALARM -> WidgetBridge.refreshPhoto(app)
                    else -> WidgetBridge.refresh(app)
                }
                refreshWidgetViews(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.ferforastieri.valkyris.widget.REFRESH"
        const val ACTION_PHOTO_ALARM = "com.ferforastieri.valkyris.widget.PHOTO_ALARM"
        const val ACTION_TOGGLE_LIGHT = "com.ferforastieri.valkyris.widget.TOGGLE_LIGHT"
        const val ACTION_REMOTE_KEY = "com.ferforastieri.valkyris.widget.REMOTE_KEY"
        const val ACTION_REMOTE_TEXT = "com.ferforastieri.valkyris.widget.REMOTE_TEXT"
        const val ACTION_REMOTE_POWER = "com.ferforastieri.valkyris.widget.REMOTE_POWER"
        const val ACTION_REMOTE_KEYPAD = "com.ferforastieri.valkyris.widget.REMOTE_KEYPAD"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
        const val EXTRA_TEXT = "text"
    }
}
