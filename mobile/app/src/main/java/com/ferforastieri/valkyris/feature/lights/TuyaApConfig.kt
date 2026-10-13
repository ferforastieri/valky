package com.ferforastieri.valkyris.feature.lights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Entrega os dados da rede de casa para a lâmpada no modo de configuração.
 *
 * O aparelho abre um ponto de acesso (o gateway que o celular recebe por DHCP)
 * e escuta um diálogo TLS com chave pré-compartilhada na porta 6668 — dentro
 * dele vai um quadro LPV3.5 com o JSON da configuração (ver TuyaApTlsPsk, feito
 * a partir do código aberto do fabricante). O sucesso é confirmado pela própria
 * resposta do aparelho e, em seguida, pela adoção no backend.
 */
internal object TuyaApConfig {
    /** Portas padrão dos pontos de acesso do fabricante, da mais comum à mais rara. */
    private val gatewaysPadrao = listOf("192.168.176.1", "192.168.4.1", "192.168.0.1")

    /**
     * Tenta entregar os dados. Devolve o nome do formato aceito quando o
     * aparelho responde que aceitou, ou nulo quando nada respondeu.
     */
    suspend fun deliver(
        network: android.net.Network,
        ssid: String,
        password: String,
        appToken: String,
        secret: String,
        region: String,
        gateways: List<String> = emptyList(),
        onAttempt: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val auth = region + appToken + secret
        val corpos = listOf(
            "auth" to """{"ssid":"$ssid","passwd":"$password","token":"$auth","reg":"$region"}""",
            "token" to """{"ssid":"$ssid","passwd":"$password","token":"$appToken","reg":"$region"}""",
        )
        val destinos = (gateways + gatewaysPadrao + advertisedAddresses(network)).distinct()
        for (endereco in destinos) {
            val aceito = TuyaApTlsPsk.entrega(network, endereco, corpos) { nome -> onAttempt(nome) }
            if (aceito != null) return@withContext aceito
        }
        null
    }

    /**
     * Ouve o anúncio que a lâmpada faz na própria rede (porta 6667) para
     * descobrir o endereço dela quando não é o padrão.
     */
    suspend fun advertisedAddresses(network: android.net.Network, waitMillis: Long = 3_000): List<String> = withContext(Dispatchers.IO) {
        val encontrados = mutableListOf<String>()
        val socket = runCatching {
            java.net.DatagramSocket().apply {
                network.bindSocket(this)
                soTimeout = 700
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(6667))
            }
        }.getOrNull() ?: return@withContext encontrados
        try {
            val buffer = ByteArray(2048)
            val fim = System.currentTimeMillis() + waitMillis
            while (System.currentTimeMillis() < fim) {
                val pacote = java.net.DatagramPacket(buffer, buffer.size)
                val ok = runCatching { socket.receive(pacote); true }.getOrDefault(false)
                if (!ok) continue
                val host = pacote.address?.hostAddress ?: continue
                if (host !in encontrados) encontrados += host
            }
        } finally {
            runCatching { socket.close() }
        }
        encontrados
    }

    /** O envio é repetido: a lâmpada pode estar reiniciando o rádio. */
    suspend fun deliverWithRetries(
        network: android.net.Network,
        ssid: String,
        password: String,
        appToken: String,
        secret: String,
        region: String,
        gateways: List<String> = emptyList(),
        rounds: Int = 2,
        onAttempt: (String) -> Unit = {},
    ): String? {
        repeat(rounds) { rodada ->
            val aceito = deliver(network, ssid, password, appToken, secret, region, gateways, onAttempt)
            if (aceito != null) return aceito
            if (rodada < rounds - 1) delay(3_000)
        }
        return null
    }
}
