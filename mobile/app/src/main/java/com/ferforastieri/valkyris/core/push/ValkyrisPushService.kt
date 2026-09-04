package com.ferforastieri.valkyris.core.push

import android.util.Base64
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.ferforastieri.valkyris.core.model.PushRegistration
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

@AndroidEntryPoint class ValkyrisPushService:PushService(){
    @Inject lateinit var api:ValkyrisApi
    @Inject lateinit var secrets:PushSecretStore
    @Inject lateinit var notifier:AlarmNotifier
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onNewEndpoint(endpoint:PushEndpoint,instance:String){scope.launch{runCatching{api.registerPush(PushRegistration(endpoint.url,secrets.getOrCreate()))}}}
    override fun onMessage(message:PushMessage,instance:String){runCatching{val wrapper=JSONObject(String(message.content));val payload=JSONObject(String(open(wrapper.getString("ciphertext"),secrets.getOrCreate())));val eventId=payload.getString("eventId");val cameraId=payload.optString("cameraId");val type=payload.optString("type","event");val confidence=payload.optDouble("confidence",0.0);notifier.show(eventId,cameraId,type,confidence,payload.optBoolean("alarm",true),payload.optString("target","event")=="camera")}}
    override fun onRegistrationFailed(reason:FailedReason,instance:String)=Unit
    override fun onUnregistered(instance:String)=Unit
    private fun open(encoded:String,secret:String):ByteArray{val sealed=Base64.decode(encoded,Base64.NO_WRAP or Base64.URL_SAFE);val key=MessageDigest.getInstance("SHA-256").digest(secret.toByteArray());val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,sealed.copyOfRange(0,12)));return cipher.doFinal(sealed.copyOfRange(12,sealed.size))}
}
