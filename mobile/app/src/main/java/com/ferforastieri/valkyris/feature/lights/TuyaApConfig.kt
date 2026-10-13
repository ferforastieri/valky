package com.ferforastieri.valkyris.feature.lights

import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Entrega os dados da rede de casa para a lâmpada no modo de configuração (AP).
 *
 * A lâmpada em modo de configuração cria a própria rede Wi-Fi e espera uma
 * conversa direta na porta 6667 — é o que a documentação do fabricante descreve
 * como o caminho de maior sucesso. O conteúdo exato varia por firmware, então os
 * formatos conhecidos são tentados em sequência; o sucesso é percebido fora
 * daqui (a lâmpada entra na rede e o backend a adota).
 */
internal object TuyaApConfig {
    const val PORT = 6667
    private const val ADVERTISEMENT_PORT = 6668

    /** Endereço padrão das lâmpadas em modo de configuração. */
    private val gateways = listOf("192.168.4.1", "192.168.0.1", "192.168.1.1")

    private const val APP_KEY = "a3c6794oiu876t54"

    /** Nomes de campo usados pelos firmwares; variam entre gerações. */
    private fun json(ssid: String, password: String, token: String, region: String): String =
        """{"s":"$ssid","p":"$password","token":"$token","region":"$region"}"""

    private fun legacyJson(ssid: String, password: String, token: String, region: String): String =
        """{"ssid":"$ssid","password":"$password","token":"$token","region":"$region"}"""

    private fun encrypted(plain: String, key: String): ByteArray {
        val block = Cipher.getInstance("AES/ECB/NoPadding")
        val raw = plain.toByteArray()
        val padded = raw.copyInto(ByteArray(((raw.size + 15) / 16) * 16))
        block.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        return block.doFinal(padded)
    }

    private fun md5(value: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 16)

    /** Formatos tentados, do mais comum ao mais raro. */
    private fun candidates(ssid: String, password: String, appToken: String, secret: String, region: String): List<Pair<String, ByteArray>> {
        val body = json(ssid, password, appToken, region)
        val legacy = legacyJson(ssid, password, appToken, region)
        val out = mutableListOf<Pair<String, ByteArray>>()
        out += "json" to body.toByteArray()
        out += "json-legado" to legacy.toByteArray()
        out += "aes-app" to encrypted(body, APP_KEY)
        out += "aes-token" to encrypted(body, md5(APP_KEY + appToken))
        out += "aes-secret" to encrypted(body, md5(secret + appToken))
        // Envelope 0F 0F + tamanho + crc + conteúdo, usado por alguns firmwares.
        listOf("json" to body.toByteArray(), "aes-app" to encrypted(body, APP_KEY)).forEach { (name, payload) ->
            out += "$name-0f0f" to framed(payload)
        }
        return out
    }

    private fun framed(payload: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(payload) }.value.toInt()
        val header = byteArrayOf(0x0F, 0x0F) + intBytes(payload.size) + intBytes(crc)
        return header + payload
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /**
     * Tenta entregar os dados. Devolve o nome do formato que foi aceito quando a
     * lâmpada responde algo, ou nulo quando nenhum formato teve resposta.
     */
    suspend fun deliver(
        network: android.net.Network,
        ssid: String,
        password: String,
        appToken: String,
        secret: String,
        region: String,
        onAttempt: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val formats = candidates(ssid, password, appToken, secret, region)
        for (address in gateways + advertisedAddresses(network)) {
            for ((name, payload) in formats) {
                onAttempt(name)
                val answered = runCatching {
                    Socket().use { socket ->
                        network.bindSocket(socket)
                        socket.connect(InetSocketAddress(address, PORT), 2500)
                        socket.soTimeout = 2500
                        socket.getOutputStream().apply { write(payload); flush() }
                        val reply = ByteArray(64)
                        val read = runCatching { socket.getInputStream().read(reply) }.getOrDefault(-1)
                        read > 0
                    }
                }.getOrDefault(false)
                if (answered) return@withContext name
            }
        }
        null
    }

    /**
     * Ouve o anúncio que a lâmpada faz na própria rede (porta 6668) para
     * descobrir o endereço dela quando não é o padrão.
     */
    suspend fun advertisedAddresses(network: android.net.Network, waitMillis: Long = 4_000): List<String> = withContext(Dispatchers.IO) {
        val found = mutableListOf<String>()
        val socket = runCatching { java.net.DatagramSocket().apply { network.bindSocket(this); soTimeout = 800; reuseAddress = true; this.broadcast = true; bind(InetSocketAddress(ADVERTISEMENT_PORT)) } }.getOrNull() ?: return@withContext found
        try {
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + waitMillis
            while (System.currentTimeMillis() < deadline) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                if (!ok) continue
                val host = packet.address?.hostAddress ?: continue
                if (host !in found) found += host
            }
        } finally {
            runCatching { socket.close() }
        }
        found
    }

    /** O envio é repetido algumas vezes: a lâmpada pode estar reiniciando o rádio. */
    suspend fun deliverWithRetries(
        network: android.net.Network,
        ssid: String,
        password: String,
        appToken: String,
        secret: String,
        region: String,
        rounds: Int = 3,
        onAttempt: (String) -> Unit = {},
    ): String? {
        repeat(rounds) { round ->
            val accepted = deliver(network, ssid, password, appToken, secret, region) { onAttempt(it) }
            if (accepted != null) return accepted
            if (round < rounds - 1) delay(3_000)
        }
        return null
    }
}
