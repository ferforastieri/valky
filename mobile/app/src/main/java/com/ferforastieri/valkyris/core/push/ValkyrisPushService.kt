package com.ferforastieri.valkyris.core.push

import android.util.Base64
import android.util.Log
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
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
            if (!isFreshPush(payload.optString("occurredAt"), message.sentTime, System.currentTimeMillis())) {
                Log.i("ValkyrisPush", "Discarding expired alert")
                return@runCatching
            }
            val alerts = payload.optJSONObject("alerts")
            notifier.show(
                cameraName = payload.optString("cameraName", ""),
                alerts = com.ferforastieri.valkyris.core.model.AlertPresentation(
                    notificationTitle = alerts?.optString("notificationTitle").orEmpty(),
                    notificationBody = alerts?.optString("notificationBody").orEmpty(),
                    alarmTitle = alerts?.optString("alarmTitle").orEmpty(),
                    alarmBody = alerts?.optString("alarmBody").orEmpty(),
                    alarmSound = alerts?.optString("alarmSound", "alarm") ?: "alarm",
                    vibrate = alerts?.optBoolean("vibrate", true) ?: true,
                    fullScreen = alerts?.optBoolean("fullScreen", true) ?: true,
                ),
                personName = payload.optString("personName", ""),
                placeName = payload.optString("placeName", ""),
                eventId = payload.getString("eventId"),
                cameraId = payload.optString("cameraId"),
                type = payload.optString("type", "event"),
                confidence = payload.optDouble("confidence", 0.0),
                alarm = payload.optBoolean("alarm", false),
                opensCamera = payload.optString("target", "event") == "camera",
            )
        }.onFailure { Log.w("ValkyrisPush", "Cannot display encrypted alert: ${it.javaClass.simpleName}") }
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

internal const val PUSH_FRESHNESS_MILLIS = 60_000L
private const val PUSH_FUTURE_TOLERANCE_MILLIS = 5 * 60_000L

internal fun isFreshPush(occurredAt: String, sentAtMillis: Long, nowMillis: Long): Boolean {
    val occurredAtMillis = runCatching { Instant.parse(occurredAt).toEpochMilli() }.getOrNull() ?: return false
    val timestamps = buildList {
        add(occurredAtMillis)
        if (sentAtMillis > 0) add(sentAtMillis)
    }
    return timestamps.all { timestamp ->
        val age = nowMillis - timestamp
        age in -PUSH_FUTURE_TOLERANCE_MILLIS..PUSH_FRESHNESS_MILLIS
    }
}
