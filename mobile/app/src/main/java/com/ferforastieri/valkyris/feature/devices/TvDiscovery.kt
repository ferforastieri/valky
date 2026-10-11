package com.ferforastieri.valkyris.feature.devices

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A TV found on the local network. */
data class DiscoveredTv(val host: String, val name: String, val model: String = "")

/**
 * Finds Samsung TVs on the same network as the phone. SSDP (UPnP discovery) is
 * the primary path because the TVs answer the RemoteControlReceiver search
 * target directly. When multicast is blocked by the access point, fall back to a
 * quick TCP probe of the local /24 on the TV REST port.
 */
object TvDiscovery {
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SAMSUNG_PORT = 8001
    private const val SAMSUNG_SECURE_PORT = 8002
    private const val PROBE_TIMEOUT_MS = 350

    suspend fun discover(context: Context): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val lock = acquireMulticastLock(context)
        try {
            // SSDP answers are trusted only when the host actually exposes the
            // TV control ports, so a DIAL response from another device (a
            // Chromecast, for example) is not mistaken for a Samsung TV.
            val ssdp = ssdpHosts().filter { reachable(it, SAMSUNG_PORT) || reachable(it, SAMSUNG_SECURE_PORT) }
            val hosts = if (ssdp.isNotEmpty()) ssdp else probeLocalSubnet()
            hosts.map { DiscoveredTv(host = it, name = "TV Samsung") }.distinctBy { it.host }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("valkyris-tv-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun ssdpHosts(): Set<String> {
        val hosts = LinkedHashSet<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                socket.broadcast = true
                val group = InetAddress.getByName(SSDP_HOST)
                listOf(
                    "urn:samsung.com:device:RemoteControlReceiver:1",
                    "urn:dial-multiscreen-org:service:dial:1",
                ).forEach { target ->
                    val request = (
                        "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $target\r\n\r\n"
                        ).toByteArray()
                    runCatching { socket.send(DatagramPacket(request, request.size, group, SSDP_PORT)) }
                }
                val deadline = System.currentTimeMillis() + 3_500
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    location?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.let { hosts += it }
                }
            }
        }
        return hosts
    }

    private fun probeLocalSubnet(): Set<String> {
        val local = localIpv4() ?: return emptySet()
        val prefix = local.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return emptySet()
        val hosts = Collections.synchronizedSet(LinkedHashSet<String>())
        val executor = Executors.newFixedThreadPool(32)
        val latch = CountDownLatch(254)
        for (index in 1..254) {
            val host = "$prefix.$index"
            executor.execute {
                try {
                    Socket().use { it.connect(InetSocketAddress(host, SAMSUNG_PORT), PROBE_TIMEOUT_MS) }
                    hosts += host
                } catch (_: Exception) {
                    // Unreachable host: expected during a subnet sweep.
                } finally {
                    latch.countDown()
                }
            }
        }
        runCatching { latch.await(6, TimeUnit.SECONDS) }
        executor.shutdownNow()
        return hosts
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull()
}
