package com.ferforastieri.valky.core.push

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class PushSecretStore @Inject constructor(@param:ApplicationContext private val context:Context){
    private val alias="valky_push_secret_v1"
    fun getOrCreate():String{val prefs=context.getSharedPreferences("push_secret",Context.MODE_PRIVATE);prefs.getString("secret",null)?.let{return decrypt(it)};val data=ByteArray(32).also{SecureRandom().nextBytes(it)};return Base64.encodeToString(data,Base64.NO_WRAP or Base64.URL_SAFE).also{prefs.edit().putString("secret",encrypt(it)).apply()}}
    private fun key():SecretKey{val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(store.getKey(alias,null)as?SecretKey)?.let{return it};val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey()}
    private fun encrypt(value:String):String{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)}
    private fun decrypt(value:String):String{val data=Base64.decode(value,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,data.copyOfRange(0,12)));return String(cipher.doFinal(data.copyOfRange(12,data.size)))}
}
