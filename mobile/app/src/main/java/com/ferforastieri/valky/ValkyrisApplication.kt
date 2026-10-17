package com.ferforastieri.valky

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.ferforastieri.valky.core.alarm.AlarmNotifier
import com.ferforastieri.valky.core.push.FcmRegistration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ValkyApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var alarmNotifier: AlarmNotifier
    @Inject lateinit var fcmRegistration: FcmRegistration

    override fun onCreate() {
        super.onCreate()
        alarmNotifier.createChannels()
        fcmRegistration.registerCurrent()
    }

    /**
     * O padrão do Coil reserva 25% da memória do aplicativo para imagens. Como
     * aqui as imagens são fotos de câmera em tamanho grande, um teto fixo de
     * 24 MB segura o uso de memória sem perder fluidez na lista.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(24L * 1024 * 1024)
                    .build()
            }
            .build()
}
