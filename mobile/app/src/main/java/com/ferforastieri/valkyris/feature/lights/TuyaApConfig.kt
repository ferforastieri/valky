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

    /** Como o aplicativo oficial: o aparelho recebe região + token + segredo juntos. */
    private fun authToken(region: String, token: String, secret: String): String = region + token + secret

    /** Nomes de campo usados pelos firmwares; variam entre gerações. */
    private fun json(ssid: String, password: String, token: String, region: String): String =
        """{"s":"$ssid","p":"$password","token":"$token","region":"$region"}"""

    private fun legacyJson(ssid: String, password: String, token: String, region: String): String =
        """{"ssid":"$ssid","password":"$password","token":"$token","region":"$region"}"""

    private fun passwdJson(ssid: String, password: String, token: String, region: String): String =
        """{"ssid":"$ssid","passwd":"$password","token":"$token","region":"$region"}"""

    private fun encrypted(plain: String, key: String): ByteArray {
        val block = Cipher.getInstance("AES/ECB/NoPadding")
        val raw = plain.toByteArray()
        val padded = raw.copyInto(ByteArray(((raw.size + 15) / 16) * 16))
        block.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        return block.doFinal(padded)
    }

    private fun md5(value: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.substring(0, 16)

    /** Formatos tentados, do mais provável ao mais raro. */
    private fun candidates(ssid: String, password: String, appToken: String, secret: String, region: String): List<Pair<String, ByteArray>> {
        val auth = authToken(region, appToken, secret)
        val comAuth = json(ssid, password, auth, region)
        val legadoAuth = legacyJson(ssid, password, auth, region)
        val passwdAuth = passwdJson(ssid, password, auth, region)
        val comToken = json(ssid, password, appToken, region)
        val out = mutableListOf<Pair<String, ByteArray>>()
        out += "json-auth" to comAuth.toByteArray()
        out += "json-legado" to legadoAuth.toByteArray()
        out += "json-passwd" to passwdAuth.toByteArray()
        out += "json-token" to comToken.toByteArray()
        out += "aes-app" to encrypted(comAuth, APP_KEY)
        out += "aes-token" to encrypted(comAuth, md5(APP_KEY + appToken))
        out += "aes-secret" to encrypted(comAuth, md5(secret + appToken))
        out += "aes-simples" to encrypted(comAuth, md5(appToken))
        // Envelope 0F 0F + tamanho + crc + conteúdo, usado por alguns firmwares.
        listOf("json-auth" to comAuth.toByteArray(), "aes-app" to encrypted(comAuth, APP_KEY)).forEach { (name, payload) ->
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

    /** Tenta o mesmo envio por uma conexão TLS (certificado do próprio aparelho). */
    private fun deliverTls(network: android.net.Network, address: String, payload: ByteArray): Boolean = runCatching {
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }), java.security.SecureRandom())
        val socket = context.socketFactory.createSocket()
        network.bindSocket(socket)
        (socket as javax.net.ssl.SSLSocket).soTimeout = 1_500
        socket.connect(InetSocketAddress(address, PORT), 1_500)
        socket.startHandshake()
        socket.getOutputStream().apply { write(payload); flush() }
        val reply = ByteArray(64)
        val read = runCatching { socket.getInputStream().read(reply) }.getOrDefault(-1)
        socket.close()
        read > 0
    }.getOrDefault(false)

    /**
     * Entrega em dois tempos, como o aplicativo oficial:
     * 1) UDP para a rede da lâmpada (o pareamento clássico vai por datagrama);
     * 2) TCP/TLS direto na porta 6667 (a geração atual usa conexão segura).
     * Devolve o nome do formato que teve resposta, ou nulo.
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
        val destinos = (gateways + advertisedAddresses(network)).distinct()
        for (porta in listOf(6667, 6669)) {
            formats.forEach { (name, payload) ->
                onAttempt("$name-udp")
                sendUdp(network, destinos, porta, payload)
            }
        }
        for (address in destinos) {
            for ((name, payload) in formats) {
                onAttempt(name)
                val answered = runCatching {
                    Socket().use { socket ->
                        network.bindSocket(socket)
                        socket.connect(InetSocketAddress(address, PORT), 1_500)
                        socket.soTimeout = 1_500
                        socket.getOutputStream().apply { write(payload); flush() }
                        val reply = ByteArray(64)
                        val read = runCatching { socket.getInputStream().read(reply) }.getOrDefault(-1)
                        read > 0
                    }
                }.getOrDefault(false)
                if (answered) return@withContext name
                if (deliverTls(network, address, payload)) return@withContext "$name-tls"
            }
        }
        null
    }

    /** Um datagrama para cada endereço conhecido e para o broadcast da rede. */
    private fun sendUdp(network: android.net.Network, destinos: List<String>, porta: Int, payload: ByteArray) {
        val socket = runCatching {
            java.net.DatagramSocket().apply { network.bindSocket(this); broadcast = true }
        }.getOrNull() ?: return
        try {
            (destinos + "255.255.255.255").distinct().forEach { alvo ->
                runCatching {
                    socket.send(java.net.DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(alvo), porta))
                }
            }
        } finally {
            runCatching { socket.close() }
        }
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
        rounds: Int = 2,
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
