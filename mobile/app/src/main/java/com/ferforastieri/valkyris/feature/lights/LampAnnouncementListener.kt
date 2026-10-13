package com.ferforastieri.valkyris.feature.lights

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * O servidor está no cabo e não escuta o ar do Wi-Fi; o celular está. Enquanto
 * o pareamento está aberto, esta escuta captura os anúncios que a lâmpada manda
 * na rede (UDP 6666, 6667 e 7000) e os entrega ao backend, que decodifica e usa
 * o identificador anunciado para adotar a lâmpada — tudo local, sem nuvem.
 */
internal class LampAnnouncementListener {
    /** Portas que os firmwares usam para se anunciar. */
    private val ports = listOf(6666, 6667, 7000)

    suspend fun listen(onAnnouncement: suspend (address: String, port: Int, payload: ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val sockets = ports.mapNotNull { port ->
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1500
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
        }
        if (sockets.isEmpty()) return@withContext
        try {
            val buffer = ByteArray(4096)
            while (sockets.isNotEmpty()) {
                for (socket in sockets.toList()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!received) continue
                    val address = packet.address?.hostAddress ?: continue
                    val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (payload.isEmpty()) continue
                    onAnnouncement(address, socket.localPort, payload)
                }
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }
}

/** Hexadecimal é o formato que a API usa para o anúncio cru. */
internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
