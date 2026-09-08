package com.ferforastieri.valkyris.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class Session(val baseUrl:String,val token:String,val fingerprint:String="",val admin:Boolean=false)

@Singleton class SessionStore @Inject constructor(@ApplicationContext context:Context) {
    private val prefs=context.getSharedPreferences("secure_session",Context.MODE_PRIVATE)
    private val keyAlias="valkyris_session_v1"
    fun get():Session? { val base=prefs.getString("base",null)?:return null;val token=decrypt(prefs.getString("token",null)?:return null);val admin=if(prefs.contains("admin"))prefs.getBoolean("admin",false)else true;return Session(base,token,prefs.getString("fingerprint","").orEmpty(),admin) }
    fun save(session:Session){prefs.edit().putString("base",session.baseUrl.trimEnd('/')).putString("token",encrypt(session.token)).putString("fingerprint",session.fingerprint).putBoolean("admin",session.admin).apply()}
    fun savedBase():String = prefs.getString("base","").orEmpty()
    fun savedFingerprint(base:String):String = if(base.trimEnd('/') == savedBase()) prefs.getString("fingerprint","").orEmpty() else ""
    fun clear(){prefs.edit().remove("token").remove("admin").apply()}
    private fun key():SecretKey{val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(store.getKey(keyAlias,null)as?SecretKey)?.let{return it};val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey()}
    private fun encrypt(value:String):String{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)}
    private fun decrypt(value:String):String{val bytes=Base64.decode(value,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)))}
}
