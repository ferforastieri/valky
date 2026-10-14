package com.ferforastieri.valkyris.feature.lights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Entrega os dados da rede de casa para a lâmpada em modo de configuração,
 * pelo caminho exato do firmware: diálogo TLS-PSK na porta 6668 do ponto de
 * acesso dela (o gateway que o celular recebeu por DHCP) com o quadro LPV3.5
 * tipo 0x14 (ver TuyaApTlsPsk). Só esse caminho — é o que o aparelho
 * implementa, e ele confirma com um código de aceite.
 */
internal object TuyaApConfig {
    /** O que aconteceu na entrega, para mostrar ao usuário. */
    data class Resultado(val enviado: Boolean, val confirmado: Boolean, val detalhe: String, val veredito: Int? = null) {
        val aceito: Boolean get() = confirmado
    }

    suspend fun deliver(
        network: android.net.Network,
        ssid: String,
        password: String,
        appToken: String,
        secret: String,
        region: String,
        gateways: List<String>,
        onAttempt: (String) -> Unit = {},
    ): Resultado = withContext(Dispatchers.IO) {
        if (gateways.isEmpty()) return@withContext Resultado(false, false, "não achei o endereço da lâmpada")
        // O aparelho recebe região, token e segredo juntos (authToken), como o
        // aplicativo oficial monta na API de pareamento da nuvem.
        val auth = region + appToken + secret
        val corpo = """{"ssid":"$ssid","passwd":"$password","token":"$auth","reg":"$region"}"""
        onAttempt("pareamento")
        var ultimo = Resultado(false, false, "sem resposta")
        for (endereco in gateways.distinct()) {
            val entrega = TuyaApTlsPsk.entrega(network, endereco, corpo) { relato -> onAttempt(relato) }
            ultimo = Resultado(entrega.enviado, entrega.confirmado, entrega.detalhe, entrega.veredito)
            if (entrega.confirmado) return@withContext ultimo
        }
        ultimo
    }

    suspend fun deliverWithRetries(
        network: android.net.Network,
        ssid: String,
        password: String,
        appToken: String,
        secret: String,
        region: String,
        gateways: List<String>,
        rounds: Int = 2,
        onAttempt: (String) -> Unit = {},
    ): Resultado {
        var ultimo = Resultado(false, false, "sem resposta")
        repeat(rounds) { rodada ->
            ultimo = deliver(network, ssid, password, appToken, secret, region, gateways, onAttempt)
            // Veredito é resposta final: repetir com os mesmos dados não muda nada.
            if (ultimo.confirmado || ultimo.veredito != null) return ultimo
            if (rodada < rounds - 1) delay(3_000)
        }
        return ultimo
    }
}
