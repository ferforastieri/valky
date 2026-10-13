package com.ferforastieri.valkyris.feature.lights

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * O pareamento do aparelho, exatamente como o firmware do fabricante pede
 * (código aberto TuyaOpen: `ap_netcfg.c`, `tuya_protocol.c`, `tuya_tls.c`):
 *
 *  - TLS 1.2 na porta 6668 com chave pré-compartilhada: PSK "123456" e
 *    identidade "psk_identity";
 *  - a única cifra da lista PSK do firmware é
 *    TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256 (0xC037);
 *  - dentro do canal vai um quadro LPV3.5 (00 00 66 99, tipo 0x14) com o
 *    conteúdo em AES-128-GCM usando a chave fixa MD5("OXLvslCvUxcTPLGO");
 *  - o conteúdo é JSON puro: {"ssid","passwd","token","reg"} — o aparelho
 *    analisa direto com cJSON;
 *  - o aparelho responde o mesmo tipo com um código (0 = aceito).
 */
internal object TuyaApTlsPsk {
    private const val PORTA = 6668
    private const val PSK = "123456"
    private const val PSK_ID = "psk_identity"
    private const val SUITE_ECDHE_PSK_CBC_SHA256 = 0xC037

    private val aleatorio = SecureRandom()

    /** O que aconteceu numa tentativa, para mostrar ao usuário. */
    data class Entrega(val aceito: Boolean, val detalhe: String) {
        companion object {
            fun falhou(endereco: String, motivo: String) = Entrega(false, "$endereco: $motivo")
        }
    }

    /** Faz o diálogo completo e envia o conteúdo; devolve o resultado. */
    fun entrega(network: android.net.Network, endereco: String, corpo: String): Entrega = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            val conectou = runCatching { socket.connect(InetSocketAddress(endereco, PORTA), 2_500) }.isSuccess
            if (!conectou) return@use Entrega.falhou(endereco, "sem conexão na porta $PORTA")
            socket.soTimeout = 4_500
            val tls = runCatching {
                Handshake(socket.getInputStream(), socket.getOutputStream()).also { it.abre() }
            }.getOrNull() ?: return@use Entrega.falhou(endereco, "TLS recusado")
            val resposta = runCatching { tls.envia(quadro(0x14, corpo)) }.getOrNull()
                ?: return@use Entrega.falhou(endereco, "enviado, sem resposta")
            val (aceito, codigo) = respostaCodigo(resposta)
            Entrega(aceito, "$endereco: respondeu $codigo")
        }
    }.getOrDefault(Entrega.falhou(endereco, "falhou"))

    /** Lê a resposta do aparelho: (aceitou, código em texto). */
    private fun respostaCodigo(resposta: ByteArray): Pair<Boolean, String> {
        val (tipo, conteudo) = lerQuadro(resposta) ?: return false to "formato desconhecido"
        if (tipo != 0x14 || conteudo.size < 4) return false to "tipo $tipo"
        val codigo = ((conteudo[0].toInt() and 0xFF) shl 24) or ((conteudo[1].toInt() and 0xFF) shl 16) or
            ((conteudo[2].toInt() and 0xFF) shl 8) or (conteudo[3].toInt() and 0xFF)
        return (codigo == 0) to String.format("0x%08X", codigo)
    }

    // ------------------------------------------------------------------ quadro LPV3.5

    private val CHAVE = MessageDigest.getInstance("MD5").digest("OXLvslCvUxcTPLGO".toByteArray())
    private val CABECA = byteArrayOf(0x00, 0x00, 0x66, 0x99.toByte())
    private val CAUDA = byteArrayOf(0x00, 0x00, 0x99.toByte(), 0x66)

    /** Monta o quadro LPV3.5 do tipo pedido com o JSON puro dentro. */
    fun quadro(tipo: Int, json: String): ByteArray {
        val conteudo = json.toByteArray()
        val naoUsado = ByteArray(12).also { aleatorio.nextBytes(it) }
        val cabecalho = ByteArray(14)
        escreveInt(cabecalho, 2, 0)
        escreveInt(cabecalho, 6, tipo)
        escreveInt(cabecalho, 10, 12 + conteudo.size + 16)

        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CHAVE, "AES"), GCMParameterSpec(128, naoUsado))
        cifra.updateAAD(cabecalho)
        val saida = cifra.doFinal(conteudo)

        val quadro = ByteArray(4 + 14 + 12 + saida.size + 4)
        System.arraycopy(CABECA, 0, quadro, 0, 4)
        System.arraycopy(cabecalho, 0, quadro, 4, 14)
        System.arraycopy(naoUsado, 0, quadro, 18, 12)
        System.arraycopy(saida, 0, quadro, 30, saida.size)
        System.arraycopy(CAUDA, 0, quadro, 30 + saida.size, 4)
        return quadro
    }

    private fun lerQuadro(bytes: ByteArray): Pair<Int, ByteArray>? = runCatching {
        var inicio = -1
        var i = 0
        while (i + 4 <= bytes.size) {
            if (bytes[i] == 0x00.toByte() && bytes[i + 1] == 0x00.toByte() && bytes[i + 2] == 0x66.toByte() && bytes[i + 3] == 0x99.toByte()) {
                inicio = i
                break
            }
            i++
        }
        if (inicio < 0 || inicio + 4 + 14 + 12 + 16 + 4 > bytes.size) return null
        val cabecalho = bytes.copyOfRange(inicio + 4, inicio + 18)
        val tipo = leInt(cabecalho, 6)
        val tamanho = leInt(cabecalho, 10)
        val fim = inicio + 18 + tamanho
        if (fim > bytes.size) return null
        val naoUsado = bytes.copyOfRange(inicio + 18, inicio + 30)
        val cifrado = bytes.copyOfRange(inicio + 30, fim - 16)
        val etiqueta = bytes.copyOfRange(fim - 16, fim)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
        cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(CHAVE, "AES"), GCMParameterSpec(128, naoUsado))
        cifra.updateAAD(cabecalho)
        tipo to cifra.doFinal(cifrado + etiqueta)
    }.getOrNull()

    private fun escreveInt(destino: ByteArray, posicao: Int, valor: Int) {
        destino[posicao] = (valor ushr 24).toByte()
        destino[posicao + 1] = (valor ushr 16).toByte()
        destino[posicao + 2] = (valor ushr 8).toByte()
        destino[posicao + 3] = valor.toByte()
    }

    private fun leInt(origem: ByteArray, posicao: Int): Int =
        ((origem[posicao].toInt() and 0xFF) shl 24) or ((origem[posicao + 1].toInt() and 0xFF) shl 16) or
            ((origem[posicao + 2].toInt() and 0xFF) shl 8) or (origem[posicao + 3].toInt() and 0xFF)

    // ------------------------------------------------------------------ TLS 1.2 ECDHE_PSK

    /**
     * Diálogo TLS 1.2 com a cifra ECDHE_PSK_AES_128_CBC_SHA256 — exatamente a
     * única da lista PSK do firmware. Sem certificados; autenticação pela
     * chave pré-compartilhada.
     */
    internal class Handshake(private val entrada: InputStream, private val saida: java.io.OutputStream) {
        private val clienteAleatorio = ByteArray(32).also { aleatorio.nextBytes(it) }
        private val historico = ByteArrayOutputStream()
        private val pendente = ByteArrayOutputStream()
        private var servidorAleatorio = ByteArray(32)
        private var macCliente = ByteArray(32)
        private var macServidor = ByteArray(32)
        private var chaveCliente = ByteArray(16)
        private var chaveServidor = ByteArray(16)
        private var seqCliente = 0L
        private var seqServidor = 0L
        private var estendido = false

        fun abre() {
            // ClientHello com as extensões que o firmware espera de um cliente
            // ECDHE: grupos (secp256r1), formato de ponto, segredo mestre
            // estendido e renegociação.
            val corpo = ByteArrayOutputStream()
            corpo.write(byteArrayOf(0x03, 0x03))
            corpo.write(clienteAleatorio)
            corpo.write(0)
            corpo.write(byteArrayOf(0x00, 0x02))
            corpo.write(byteArrayOf((SUITE_ECDHE_PSK_CBC_SHA256 ushr 8).toByte(), (SUITE_ECDHE_PSK_CBC_SHA256 and 0xFF).toByte()))
            corpo.write(byteArrayOf(0x01, 0x00))
            val extensoes = ByteArrayOutputStream()
            extensao(extensoes, 0x000A, byteArrayOf(0x00, 0x02, 0x00, 0x17)) // grupos: secp256r1
            extensao(extensoes, 0x000B, byteArrayOf(0x01, 0x00)) // ponto não comprimido
            extensao(extensoes, 0x0017, ByteArray(0)) // segredo mestre estendido
            extensao(extensoes, 0xFF01, byteArrayOf(0x00)) // renegociação segura
            val exts = extensoes.toByteArray()
            corpo.write((exts.size ushr 8) and 0xFF)
            corpo.write(exts.size and 0xFF)
            corpo.write(exts)
            enviaHandshake(1, corpo.toByteArray())

            // ServerHello
            val servidorHello = proximaMensagem(2)
            servidorAleatorio = servidorHello.copyOfRange(2, 34)
            var pos = 35
            val tamSessao = servidorHello[pos - 1].toInt() and 0xFF
            pos += tamSessao
            val cifra = ((servidorHello[pos].toInt() and 0xFF) shl 8) or (servidorHello[pos + 1].toInt() and 0xFF)
            require(cifra == SUITE_ECDHE_PSK_CBC_SHA256) { "cifra inesperada: $cifra" }
            pos += 2 + 1
            if (pos + 2 <= servidorHello.size) {
                val tamExt = ((servidorHello[pos].toInt() and 0xFF) shl 8) or (servidorHello[pos + 1].toInt() and 0xFF)
                pos += 2
                val fim = minOf(pos + tamExt, servidorHello.size)
                while (pos + 4 <= fim) {
                    val tipo = ((servidorHello[pos].toInt() and 0xFF) shl 8) or (servidorHello[pos + 1].toInt() and 0xFF)
                    val tam = ((servidorHello[pos + 2].toInt() and 0xFF) shl 8) or (servidorHello[pos + 3].toInt() and 0xFF)
                    if (tipo == 0x0017) estendido = true
                    pos += 4 + tam
                }
            }

            // ServerKeyExchange (ECDHE) e ServerHelloDone
            var pontoServidor: ECPoint? = null
            while (true) {
                val dados = proximaMensagemLivre() ?: continue
                val tipo = dados.tipo
                if (tipo == 12) {
                    val mensagem = dados.corpo
                    // RFC 5489: a dica de identidade vem antes dos parâmetros
                    // da curva no ServerKeyExchange.
                    val dica = ((mensagem[0].toInt() and 0xFF) shl 8) or (mensagem[1].toInt() and 0xFF)
                    val base = 2 + dica
                    require(base + 4 < mensagem.size && mensagem[base] == 0x03.toByte()) {
                        "curva inesperada: " + mensagem.take(10).joinToString("") { "%02x".format(it) }
                    }
                    val curva = ((mensagem[base + 1].toInt() and 0xFF) shl 8) or (mensagem[base + 2].toInt() and 0xFF)
                    require(curva == 0x0017) { "curva inesperada: $curva" }
                    val tamPonto = mensagem[base + 3].toInt() and 0xFF
                    val ponto = mensagem.copyOfRange(base + 4, base + 4 + tamPonto)
                    require(ponto.size == 65 && ponto[0] == 0x04.toByte()) { "ponto inesperado" }
                    val x = BigInteger(1, ponto.copyOfRange(1, 33))
                    val y = BigInteger(1, ponto.copyOfRange(33, 65))
                    pontoServidor = ECPoint(x, y)
                }
                if (tipo == 14) break
            }
            val ponto = pontoServidor ?: error("sem chave do servidor")

            // Nossa chave ECDHE e o ClientKeyExchange
            val gerador = KeyPairGenerator.getInstance("EC")
            gerador.initialize(ECGenParameterSpec("secp256r1"))
            val nosso = gerador.generateKeyPair()
            val publico = (nosso.public as java.security.interfaces.ECPublicKey).w
            val nossoPonto = byteArrayOf(0x04) + para32(publico.affineX) + para32(publico.affineY)
            // RFC 5489: a identidade vem antes da chave pública no CKE.
            val identidadeBytes = PSK_ID.toByteArray()
            val cke = ByteArrayOutputStream()
            cke.write((identidadeBytes.size ushr 8) and 0xFF)
            cke.write(identidadeBytes.size and 0xFF)
            cke.write(identidadeBytes)
            cke.write(nossoPonto.size)
            cke.write(nossoPonto)
            enviaHandshake(16, cke.toByteArray())

            // Segredo: ECDH + PSK (RFC 5489)
            val parametros = AlgorithmParameters.getInstance("EC")
            parametros.init(ECGenParameterSpec("secp256r1"))
            val spec = parametros.getParameterSpec(ECParameterSpec::class.java)
            val chavePublica = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ponto, spec))
            val acordo = KeyAgreement.getInstance("ECDH")
            acordo.init(nosso.private)
            acordo.doPhase(chavePublica, true)
            val compartilhado = para32(BigInteger(1, acordo.generateSecret()))
            val psk = PSK.toByteArray()
            // RFC 5489: pré-mestre = (tamanho + Z) + (tamanho + PSK).
            val preMestra = ByteArrayOutputStream().apply {
                write((compartilhado.size ushr 8) and 0xFF); write(compartilhado.size and 0xFF); write(compartilhado)
                write((psk.size ushr 8) and 0xFF); write(psk.size and 0xFF); write(psk)
            }.toByteArray()

            val resumo = MessageDigest.getInstance("SHA-256").digest(historico.toByteArray())
            val sementeMestra = if (estendido) {
                "extended master secret".toByteArray() + resumo
            } else {
                "master secret".toByteArray() + clienteAleatorio + servidorAleatorio
            }
            val mestra = prf(preMestra, sementeMestra, 48)
            val material = prf(mestra, "key expansion".toByteArray() + servidorAleatorio + clienteAleatorio, 96)
            macCliente = material.copyOfRange(0, 32)
            macServidor = material.copyOfRange(32, 64)
            chaveCliente = material.copyOfRange(64, 80)
            chaveServidor = material.copyOfRange(80, 96)

            // ChangeCipherSpec + Finished
            enviaRegistro(20, byteArrayOf(1))
            val verificado = prf(mestra, "client finished".toByteArray() + resumo, 12)
            val nossoFinished = mensagem(20, verificado)
            enviaRegistroCifrado(22, nossoFinished)
            historico.write(nossoFinished)

            // ChangeCipherSpec + Finished do servidor (conferido)
            val (tipoCcs, corpoCcs) = leRegistro()
            require(tipoCcs == 20) {
                "sem troca de cifra: tipo $tipoCcs bytes " + corpoCcs.take(8).joinToString("") { "%02x".format(it) } + " ems=$estendido"
            }
            val (tipoFin, corpoFin) = leRegistroCifrado()
            require(tipoFin == 22 && corpoFin.size >= 16) { "finished inesperado" }
            val resumoFinal = MessageDigest.getInstance("SHA-256").digest(historico.toByteArray())
            val esperado = prf(mestra, "server finished".toByteArray() + resumoFinal, 12)
            require(corpoFin.copyOfRange(4, 16).contentEquals(esperado)) { "finished do servidor não confere" }
        }

        /** Manda o quadro pelos dados de aplicação e espera a resposta. */
        fun envia(dados: ByteArray): ByteArray? {
            enviaRegistroCifrado(23, dados)
            repeat(4) {
                val (tipo, conteudo) = leRegistroCifrado()
                if (tipo == 23) return conteudo
            }
            return null
        }

        private fun extensao(destino: ByteArrayOutputStream, tipo: Int, conteudo: ByteArray) {
            destino.write((tipo ushr 8) and 0xFF)
            destino.write(tipo and 0xFF)
            destino.write((conteudo.size ushr 8) and 0xFF)
            destino.write(conteudo.size and 0xFF)
            destino.write(conteudo)
        }

        private fun prf(segredo: ByteArray, semente: ByteArray, tamanho: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(segredo, "HmacSHA256"))
            val saida = ByteArrayOutputStream()
            var a = mac.doFinal(semente)
            while (saida.size() < tamanho) {
                mac.reset(); mac.update(a); mac.update(semente)
                saida.write(mac.doFinal())
                mac.reset(); a = mac.doFinal(a)
            }
            return saida.toByteArray().copyOf(tamanho)
        }

        private fun mensagem(tipo: Int, corpo: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tipo)
            out.write((corpo.size ushr 16) and 0xFF)
            out.write((corpo.size ushr 8) and 0xFF)
            out.write(corpo.size and 0xFF)
            out.write(corpo)
            return out.toByteArray()
        }

        private fun enviaHandshake(tipo: Int, corpo: ByteArray) {
            val msg = mensagem(tipo, corpo)
            historico.write(msg)
            enviaRegistro(22, msg)
        }

        private class MensagemHandshake(val tipo: Int, val corpo: ByteArray)

        /** Lê a próxima mensagem de handshake de um tipo específico. */
        private fun proximaMensagem(tipoEsperado: Int): ByteArray {
            while (true) {
                val dados = proximaMensagemLivre() ?: continue
                if (dados.tipo == tipoEsperado) return dados.corpo
            }
        }

        private fun proximaMensagemLivre(): MensagemHandshake? {
            while (true) {
                val acumulado = pendente.toByteArray()
                if (acumulado.size >= 4) {
                    val tamanho = ((acumulado[1].toInt() and 0xFF) shl 16) or ((acumulado[2].toInt() and 0xFF) shl 8) or (acumulado[3].toInt() and 0xFF)
                    if (acumulado.size >= 4 + tamanho) {
                        val msg = acumulado.copyOfRange(0, 4 + tamanho)
                        pendente.reset()
                        pendente.write(acumulado, 4 + tamanho, acumulado.size - (4 + tamanho))
                        historico.write(msg)
                        return MensagemHandshake(msg[0].toInt() and 0xFF, msg.copyOfRange(4, msg.size))
                    }
                }
                val (tipo, dados) = leRegistro()
                if (tipo == 22) pendente.write(dados)
                if (tipo == 20) return null
            }
        }

        private fun enviaRegistro(tipo: Int, dados: ByteArray) {
            val out = ByteArrayOutputStream()
            out.write(tipo)
            out.write(byteArrayOf(0x03, 0x03))
            out.write((dados.size ushr 8) and 0xFF)
            out.write(dados.size and 0xFF)
            out.write(dados)
            saida.write(out.toByteArray())
            saida.flush()
        }

        private fun enviaRegistroCifrado(tipo: Int, dados: ByteArray) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(macCliente, "HmacSHA256"))
            mac.update(cabecalhoMac(seqCliente, tipo, dados.size))
            mac.update(dados)
            val assinatura = mac.doFinal()
            val comMac = dados + assinatura
            // Preenchimento do TLS: "padding_length" bytes com esse valor,
            // mais um byte final igual a ele (RFC 5246, 6.2.3.2).
            val total = comMac.size
            val folga = (16 - ((total + 1) % 16)) % 16
            val preenchido = comMac + ByteArray(folga + 1) { folga.toByte() }
            val iv = ByteArray(16).also { aleatorio.nextBytes(it) }
            val cifra = Cipher.getInstance("AES/CBC/NoPadding")
            cifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chaveCliente, "AES"), IvParameterSpec(iv))
            val registro = iv + cifra.doFinal(preenchido)
            val cabecalho = byteArrayOf(tipo.toByte(), 0x03, 0x03, ((registro.size ushr 8) and 0xFF).toByte(), (registro.size and 0xFF).toByte())
            saida.write(cabecalho)
            saida.write(registro)
            saida.flush()
            seqCliente++
        }

        private fun leRegistro(): Pair<Int, ByteArray> {
            val cabecalho = leBytes(5)
            val tipo = cabecalho[0].toInt() and 0xFF
            val tamanho = ((cabecalho[3].toInt() and 0xFF) shl 8) or (cabecalho[4].toInt() and 0xFF)
            val dados = leBytes(tamanho)
            return tipo to dados
        }

        private fun leRegistroCifrado(): Pair<Int, ByteArray> {
            val (tipo, registro) = leRegistro()
            if (tipo == 20) return tipo to ByteArray(0)
            require(registro.size > 32) { "registro curto" }
            val iv = registro.copyOfRange(0, 16)
            val cifra = Cipher.getInstance("AES/CBC/NoPadding")
            cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(chaveServidor, "AES"), IvParameterSpec(iv))
            val aberto = cifra.doFinal(registro.copyOfRange(16, registro.size))
            val preenchimento = aberto.last().toInt() and 0xFF
            require(preenchimento + 1 <= aberto.size) { "preenchimento inválido" }
            val comMac = aberto.copyOfRange(0, aberto.size - (preenchimento + 1))
            val dados = comMac.copyOfRange(0, comMac.size - 32)
            seqServidor++
            return tipo to dados
        }

        private fun cabecalhoMac(sequencia: Long, tipo: Int, tamanho: Int): ByteArray {
            val cabecalho = ByteArray(13)
            var s = sequencia
            for (i in 7 downTo 0) { cabecalho[i] = (s and 0xFF).toByte(); s = s ushr 8 }
            cabecalho[8] = tipo.toByte()
            cabecalho[9] = 0x03
            cabecalho[10] = 0x03
            cabecalho[11] = ((tamanho ushr 8) and 0xFF).toByte()
            cabecalho[12] = (tamanho and 0xFF).toByte()
            return cabecalho
        }

        private fun leBytes(quantidade: Int): ByteArray {
            val dados = ByteArray(quantidade)
            var lidos = 0
            while (lidos < quantidade) {
                val n = entrada.read(dados, lidos, quantidade - lidos)
                if (n <= 0) error("fim da conexão")
                lidos += n
            }
            return dados
        }

        private fun para32(valor: BigInteger): ByteArray {
            val bytes = valor.toByteArray()
            val limpos = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
            val saida = ByteArray(32)
            System.arraycopy(limpos, 0, saida, 32 - limpos.size, limpos.size)
            return saida
        }
    }
}
