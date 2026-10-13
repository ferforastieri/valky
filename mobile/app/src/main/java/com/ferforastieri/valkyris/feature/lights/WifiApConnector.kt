package com.ferforastieri.valkyris.feature.lights

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Cuida do Wi-Fi do celular durante o pareamento: entra na rede que a lâmpada
 * cria enquanto está piscando e sai dela quando termina.
 *
 * Esse caminho não depende do roteador nem da faixa de 2,4 GHz — o celular fala
 * direto com a lâmpada. É o modo que o próprio fabricante recomenda como o mais
 * confiável.
 */
internal class WifiApConnector(context: Context) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    /** O caminho direto existe a partir do Android 10. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Nomes típicos das redes que as lâmpadas criam para configuração. */
    private val lampPrefixes = listOf("smartlife", "tuya", "neo", "avant", "pera", "esp", "t-")

    fun canScan(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // A permissão é conferida logo abaixo (canScan). O lint não enxerga isso.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanned(): List<String> = runCatching {
        if (!canScan()) return emptyList()
        wifi?.scanResults.orEmpty().mapNotNull { it.SSID?.trim('"') }.filter { it.isNotBlank() }.distinct().sorted()
    }.getOrDefault(emptyList())

    /** Redes próximas que parecem ser de uma lâmpada em modo de configuração. */
    fun candidateNetworks(): List<String> =
        scanned().filter { name -> lampPrefixes.any { prefix -> name.lowercase().startsWith(prefix) } }

    /** Todas as redes visíveis, para o usuário escolher quando o nome é incomum. */
    fun visibleNetworks(): List<String> = scanned()

    /** Rede da lâmpada conectada; fechar devolve o celular à rede anterior. */
    internal class LampNetwork(
        val network: Network,
        val ssid: String,
        private val manager: ConnectivityManager,
        private val callback: ConnectivityManager.NetworkCallback,
    ) {
        fun close() {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun connect(ssid: String, timeoutMillis: Long = 60_000): LampNetwork? = withContext(Dispatchers.IO) {
        val manager = connectivity ?: return@withContext null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).build())
            .build()
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    private var resumed = false

                    override fun onAvailable(network: Network) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(LampNetwork(network, ssid, manager, this))
                        }
                    }

                    override fun onUnavailable() {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(null)
                        }
                    }
                }
                manager.requestNetwork(request, callback)
                continuation.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(callback) } }
            }
        }
    }
}
