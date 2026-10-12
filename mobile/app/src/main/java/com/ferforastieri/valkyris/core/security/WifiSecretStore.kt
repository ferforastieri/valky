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

/**
 * Guarda a senha da rede usada no pareamento das lâmpadas. O sistema não
 * permite que um aplicativo leia a senha salva do Wi-Fi, então ela é digitada
 * uma única vez e fica aqui, cifrada pela chave do Android Keystore do aparelho,
 * para os próximos pareamentos serem automáticos.
 */
@Singleton
class WifiSecretStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val alias = "valkyris_wifi_pairing_v1"

    data class Credentials(val ssid: String, val password: String)

    fun credentials(): Credentials? {
        val prefs = context.getSharedPreferences("wifi_pairing", Context.MODE_PRIVATE)
        val ssid = prefs.getString("ssid", null)?.let { decrypt(it) } ?: return null
        val password = prefs.getString("password", null)?.let { decrypt(it) } ?: return null
        if (ssid.isBlank() || password.isBlank()) return null
        return Credentials(ssid, password)
    }

    fun save(ssid: String, password: String) {
        val prefs = context.getSharedPreferences("wifi_pairing", Context.MODE_PRIVATE)
        prefs.edit().putString("ssid", encrypt(ssid)).putString("password", encrypt(password)).apply()
    }

    fun clear() {
        context.getSharedPreferences("wifi_pairing", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)))
    }
}
