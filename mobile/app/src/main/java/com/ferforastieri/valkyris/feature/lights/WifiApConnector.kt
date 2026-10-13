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

    /**
     * Força uma varredura nova e espera o resultado. O Android guarda a última
     * lista e ela pode não incluir a rede que a lâmpada acabou de criar, então
     * esperar o aviso de "varredura pronta" é o que garante que ela apareça.
     */
    @SuppressLint("MissingPermission")
    suspend fun refreshScan(waitMillis: Long = 8_000) = withContext(Dispatchers.IO) {
        if (!canScan()) return@withContext
        val manager = wifi ?: return@withContext
        // Duas chances com espera menor: o Android limita as varreduras por
        // aplicativo e às vezes ignora o pedido; o aviso de "varredura pronta"
        // também chega para as varreduras do próprio sistema, que sempre
        // enxergam a lâmpada.
        repeat(2) {
            val ready = java.util.concurrent.CountDownLatch(1)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    ready.countDown()
                }
            }
            runCatching {
                appContext.registerReceiver(receiver, android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            }
            runCatching { manager.startScan() }
            val pronto = runCatching { ready.await(waitMillis / 2, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            runCatching { appContext.unregisterReceiver(receiver) }
            if (!pronto) kotlinx.coroutines.delay(1_200)
        }
    }

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

    /** O nome parece de uma lâmpada em modo de configuração (SMARTLIFE, NEO, …). */
    fun looksLikeLamp(ssid: String): Boolean = lampPrefixes.any { ssid.lowercase().startsWith(it) }

    /** A rede Wi-Fi em uso agora (quando o usuário conecta pelo sistema). */
    @Suppress("DEPRECATION")
    fun wifiNetwork(): Network? = runCatching {
        connectivity?.allNetworks?.firstOrNull { n ->
            connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrNull()

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
                var resumed = false
                val callback = object : ConnectivityManager.NetworkCallback() {
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
                // Sem a permissão certa o Android lança exceção aqui: melhor
                // virar "não deu" do que fechar o aplicativo.
                val registered = runCatching { manager.requestNetwork(request, callback) }.isSuccess
                if (!registered && !resumed) {
                    resumed = true
                    continuation.resume(null)
                }
                continuation.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(callback) } }
            }
        }
    }
}
