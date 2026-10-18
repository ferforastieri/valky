package com.ferforastieri.valky

import android.app.Application
import android.util.Log
import com.ferforastieri.valky.core.alarm.AlarmNotifier
import com.ferforastieri.valky.core.push.FcmRegistration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ValkyApplication : Application() {
    @Inject lateinit var alarmNotifier: AlarmNotifier
    @Inject lateinit var fcmRegistration: FcmRegistration

    override fun onCreate() {
        super.onCreate()
        // Nada no onCreate da Application pode derrubar o app: um canal de
        // notificação indisponível não pode impedir a abertura.
        runCatching { alarmNotifier.createChannels() }
            .onFailure { Log.w("ValkyApp", "Notification channels unavailable: ${it.javaClass.simpleName}") }
        fcmRegistration.registerCurrent()
    }
}
