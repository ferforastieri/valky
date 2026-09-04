package com.ferforastieri.valkyris.core.push

import android.content.Context
import com.ferforastieri.valkyris.core.model.PushRegistration
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FcmRegistration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ValkyrisApi,
    private val secrets: PushSecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun registerCurrent() {
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) {
            return
        }
        FirebaseMessaging.getInstance().register()
    }

    fun register(token: String) {
        if (token.isBlank()) return
        scope.launch {
            runCatching {
                api.registerPush(PushRegistration(token, secrets.getOrCreate()))
            }
        }
    }
}
