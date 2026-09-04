package com.ferforastieri.valkyris.core.push

import android.util.Base64
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

@AndroidEntryPoint
class ValkyrisPushService : FirebaseMessagingService() {
    @Inject lateinit var registration: FcmRegistration
    @Inject lateinit var secrets: PushSecretStore
    @Inject lateinit var notifier: AlarmNotifier

    override fun onRegistered(token: String) {
        registration.register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val ciphertext = message.data["ciphertext"] ?: return
        runCatching {
            val payload = JSONObject(String(open(ciphertext, secrets.getOrCreate())))
            notifier.show(
                eventId = payload.getString("eventId"),
                cameraId = payload.optString("cameraId"),
                type = payload.optString("type", "event"),
                confidence = payload.optDouble("confidence", 0.0),
                alarm = payload.optBoolean("alarm", true),
                opensCamera = payload.optString("target", "event") == "camera",
            )
        }
    }

    private fun open(encoded: String, secret: String): ByteArray {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val key = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, sealed.copyOfRange(0, 12)),
        )
        return cipher.doFinal(sealed.copyOfRange(12, sealed.size))
    }
}
