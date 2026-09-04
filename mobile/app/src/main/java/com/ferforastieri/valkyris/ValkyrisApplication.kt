package com.ferforastieri.valkyris

import android.app.Application
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.ferforastieri.valkyris.core.push.FcmRegistration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ValkyrisApplication : Application() {
    @Inject lateinit var alarmNotifier: AlarmNotifier
    @Inject lateinit var fcmRegistration: FcmRegistration
    override fun onCreate() {
        super.onCreate()
        alarmNotifier.createChannels()
        fcmRegistration.registerCurrent()
    }
}
