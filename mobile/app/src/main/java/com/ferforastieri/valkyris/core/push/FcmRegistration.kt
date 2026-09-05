package com.ferforastieri.valkyris.core.push

import android.content.Context
import android.util.Log
import com.ferforastieri.valkyris.core.security.SessionStore
import com.ferforastieri.valkyris.core.model.PushRegistration
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FcmRegistration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ValkyrisApi,
    private val secrets: PushSecretStore,
    private val sessions: SessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registrationJob: Job? = null

    fun registerCurrent() {
        if (sessions.get() == null) return
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) {
            Log.w("ValkyrisPush", "Firebase configuration is missing from this APK")
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().register().addOnFailureListener {
                Log.w("ValkyrisPush", "Firebase registration failed: ${it.javaClass.simpleName}")
            }
        }.onFailure { Log.w("ValkyrisPush", "Firebase initialization failed: ${it.javaClass.simpleName}") }
    }

    @Synchronized
    fun register(token: String) {
        if (token.isBlank()) return
        val session = sessions.get() ?: return
        registrationJob?.cancel()
        registrationJob = scope.launch {
            repeat(6) { attempt ->
                if (sessions.get() != session) return@launch
                try {
                    api.registerPush(PushRegistration(token, secrets.getOrCreate()))
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("ValkyrisPush", "Server registration failed (attempt ${attempt + 1}): ${error.javaClass.simpleName}")
                }
                if (attempt < 5) delay(2_000L shl attempt)
            }
        }
    }
}
