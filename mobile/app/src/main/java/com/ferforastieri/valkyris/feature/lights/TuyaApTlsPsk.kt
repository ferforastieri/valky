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
 * O pareamento do aparelho, como o firmware do fabricante pede (código aberto
 * TuyaOpen: `ap_netcfg.c`, `tuya_protocol.c`, `tuya_tls.c`):
 *
 *  - TLS 1.2 na porta 6668 com chave pré-compartilhada (PSK "123456",
 *    identidade "psk_identity");
 *  - o aplicativo oferece as cifras PSK das gerações do firmware e segue a
 *    que a lâmpada escolher: ECDHE_PSK_AES_128_CBC_SHA256 (0xC037),
 *    ECDHE_PSK_AES_128_CBC_SHA (0xC035) e PSK_AES_128_GCM_SHA256 (0x00A8);
 *  - dentro do canal vai um quadro LPV3.5 (00 00 66 99, tipo 0x14) com o
 *    conteúdo em AES-128-GCM com a chave fixa MD5("OXLvslCvUxcTPLGO");
 *  - o conteúdo é o JSON puro {ssid, passwd, token, reg};
 *  - o aparelho responde o mesmo tipo com um código (0 = aceito).
 */
internal object TuyaApTlsPsk {
    private const val PORTA = 6668
    private const val PSK = "123456"
    private const val PSK_ID = "psk_identity"

    private const val CIFRA_ECDHE_PSK_CBC_SHA256 = 0xC037
    private const val CIFRA_ECDHE_PSK_CBC_SHA = 0xC035
    private const val CIFRA_PSK_GCM_SHA256 = 0x00A8

    private val aleatorio = SecureRandom()

    /**
     * O que aconteceu numa tentativa: [enviado] diz que a mensagem saiu do
     * celular; [confirmado] que a própria lâmpada respondeu código 0.
     */
    data class Entrega(val enviado: Boolean, val confirmado: Boolean, val detalhe: String) {
        companion object {
            fun falhou(endereco: String, motivo: String) = Entrega(false, false, "$endereco: $motivo")
        }
    }

    /**
     * Entrega pelo caminho da geração da lâmpada, na ordem:
     * 1) TLS-PSK na porta 6668 (firmware atual);
     * 2) o quadro LPV3.5 direto no TCP 6668 (geração anterior);
     * 3) o quadro por UDP no 6667 (clássico, que também é transmitido).
     */
    fun entrega(network: android.net.Network, endereco: String, corpo: String): Entrega {
        val quadroBytes = quadro(0x14, corpo)
        // Cada caminho usa uma conexão própria: mandar o quadro direto no
        // mesmo canal onde o TLS já foi tentado estragaria o fluxo.
        // 1) TLS-PSK na 6668 (firmware atual)
        val viaTls = comSocket(network, endereco) { socket ->
            socket.soTimeout = 2_500
            val dialogo = Handshake(socket.getInputStream(), socket.getOutputStream())
            dialogo.abre()
            dialogo.envia(quadroBytes)
        }
        if (viaTls != null) {
            val (confirmado, codigo) = respostaCodigo(viaTls)
            return Entrega(true, confirmado, "$endereco: tls-psk respondeu $codigo")
        }
        // 2) quadro LPV3.5 direto no TCP 6668 (geração anterior) — conexão nova
        val viaDireto = comSocket(network, endereco) { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply { write(quadroBytes); flush() }
            lerAte(socket.getInputStream(), 3_000)
        }
        if (viaDireto != null) {
            val (confirmado, codigo) = respostaCodigo(viaDireto)
            return Entrega(true, confirmado, "$endereco: tcp-direto respondeu $codigo")
        }
        // 3) clássico: datagrama no UDP 6667 (lâmpada e broadcast)
        val (enviado, respostaUdp) = enviaUdp(network, endereco, quadroBytes)
        if (!enviado) return Entrega.falhou(endereco, "sem conexão (tcp e udp)")
        if (respostaUdp != null) {
            val (confirmado, codigo) = respostaCodigo(respostaUdp)
            return Entrega(true, confirmado, "$endereco: udp respondeu $codigo")
        }
        return Entrega(true, false, "$endereco: udp enviado (sem resposta)")
    }

    /** Abre uma conexão nova com a lâmpada e roda o passo pedido. */
    private fun <T> comSocket(network: android.net.Network, endereco: String, passo: (Socket) -> T?): T? = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            if (!runCatching { socket.connect(InetSocketAddress(endereco, PORTA), 2_500) }.isSuccess) return null
            passo(socket)
        }
    }.getOrNull()

    /** Lê o que chegar até o tempo acabar (um quadro pode vir picado). */
    private fun lerAte(entrada: java.io.InputStream, millis: Int): ByteArray? {
        val total = java.io.ByteArrayOutputStream()
        val fim = System.currentTimeMillis() + millis
        val buffer = ByteArray(512)
        while (System.currentTimeMillis() < fim && total.size() < 2048) {
            val lidos = runCatching { entrada.read(buffer) }.getOrDefault(-1)
            if (lidos <= 0) break
            total.write(buffer, 0, lidos)
        }
        return if (total.size() > 0) total.toByteArray() else null
    }

    /** Datagrama para a lâmpada e para o broadcast; espera breve por resposta. */
    private fun enviaUdp(network: android.net.Network, endereco: String, quadroBytes: ByteArray): Pair<Boolean, ByteArray?> = runCatching {
        java.net.DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.broadcast = true
            socket.soTimeout = 1_200
            listOf(endereco, "255.255.255.255").forEach { alvo ->
                runCatching {
                    socket.send(java.net.DatagramPacket(quadroBytes, quadroBytes.size, java.net.InetAddress.getByName(alvo), 6667))
                }
            }
            val buffer = ByteArray(2048)
            val pacote = java.net.DatagramPacket(buffer, buffer.size)
            val recebeu = runCatching { socket.receive(pacote); true }.getOrDefault(false)
            true to (if (recebeu) pacote.data.copyOf(pacote.length) else null)
        }
    }.getOrDefault(false to null)

    /**
     * Lê a resposta do aparelho: (aceitou, descrição). Entende o quadro
     * LPV3.5 (gerações atuais) e o quadro clássico 55 AA (geração anterior);
     * quando não reconhece a moldura, descreve os bytes para o relato.
     */
    private fun respostaCodigo(resposta: ByteArray): Pair<Boolean, String> {
        lerQuadro(resposta)?.let { (tipo, conteudo) ->
            if (tipo == 0x14 && conteudo.size >= 4) {
                val codigo = ((conteudo[0].toInt() and 0xFF) shl 24) or ((conteudo[1].toInt() and 0xFF) shl 16) or
                    ((conteudo[2].toInt() and 0xFF) shl 8) or (conteudo[3].toInt() and 0xFF)
                return (codigo == 0) to String.format("0x%08X", codigo)
            }
            return false to "lvp35 tipo $tipo"
        }
        // quadro clássico: 00 00 55 AA · sequência · comando · tamanho · dados
        val inicio = procura(resposta, byteArrayOf(0x00, 0x00, 0x55, 0xAA.toByte()))
        if (inicio >= 0 && resposta.size >= inicio + 16) {
            val comando = leInt(resposta, inicio + 8)
            val tamanho = leInt(resposta, inicio + 12)
            val dados = resposta.copyOfRange(minOf(inicio + 16, resposta.size), minOf(inicio + 16 + tamanho, resposta.size))
            return false to ("55aa cmd=" + comando + " " + dados.take(40).joinToString("") { "%02x".format(it) })
        }
        return false to (resposta.size.toString() + "b " + resposta.take(24).joinToString("") { "%02x".format(it) })
    }

    /** Onde o padrão aparece na resposta (ou -1). */
    private fun procura(bytes: ByteArray, padrao: ByteArray): Int {
        var i = 0
        while (i + padrao.size <= bytes.size) {
            var igual = true
            padrao.forEachIndexed { j, b -> if (bytes[i + j] != b) igual = false }
            if (igual) return i
            i++
        }
        return -1
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

    // ------------------------------------------------------------------ TLS 1.2 com PSK

    /**
     * Diálogo TLS 1.2 somente com chave pré-compartilhada (sem certificados),
     * seguindo a cifra que o aparelho escolher entre as oferecidas.
     */
    internal class Handshake(private val entrada: InputStream, private val saida: java.io.OutputStream) {
        private val clienteAleatorio = ByteArray(32).also { aleatorio.nextBytes(it) }
        private val historico = ByteArrayOutputStream()
        private val pendente = ByteArrayOutputStream()
        private var servidorAleatorio = ByteArray(32)
        private var ecdhe = true
        private var gcm = false
        private var algoritmoMac = "HmacSHA256"
        private var tamanhoMac = 32
        private var macCliente = ByteArray(0)
        private var macServidor = ByteArray(0)
        private var chaveCliente = ByteArray(16)
        private var chaveServidor = ByteArray(16)
        private var ivCliente = ByteArray(4)
        private var ivServidor = ByteArray(4)
        private var seqCliente = 0L
        private var seqServidor = 0L
        private var estendido = false

        fun abre() {
            // ClientHello: oferece as cifras PSK que os firmwares usam, os
            // grupos, o formato de ponto, o segredo estendido e a renegociação.
            val corpo = ByteArrayOutputStream()
            corpo.write(byteArrayOf(0x03, 0x03))
            corpo.write(clienteAleatorio)
            corpo.write(0)
            corpo.write(byteArrayOf(0x00, 0x06))
            corpo.write(byteArrayOf((CIFRA_ECDHE_PSK_CBC_SHA256 ushr 8).toByte(), (CIFRA_ECDHE_PSK_CBC_SHA256 and 0xFF).toByte()))
            corpo.write(byteArrayOf((CIFRA_ECDHE_PSK_CBC_SHA ushr 8).toByte(), (CIFRA_ECDHE_PSK_CBC_SHA and 0xFF).toByte()))
            corpo.write(byteArrayOf((CIFRA_PSK_GCM_SHA256 ushr 8).toByte(), (CIFRA_PSK_GCM_SHA256 and 0xFF).toByte()))
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
            when (cifra) {
                CIFRA_ECDHE_PSK_CBC_SHA256 -> {
                    ecdhe = true; gcm = false; algoritmoMac = "HmacSHA256"; tamanhoMac = 32
                }
                CIFRA_ECDHE_PSK_CBC_SHA -> {
                    ecdhe = true; gcm = false; algoritmoMac = "HmacSHA1"; tamanhoMac = 20
                }
                CIFRA_PSK_GCM_SHA256 -> {
                    ecdhe = false; gcm = true; algoritmoMac = ""; tamanhoMac = 0
                }
                else -> error("cifra inesperada: " + String.format("0x%04X", cifra))
            }
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

            // ServerKeyExchange (só nas cifras ECDHE) e ServerHelloDone
            var pontoServidor: ECPoint? = null
            while (true) {
                val dados = proximaMensagemLivre() ?: continue
                if (dados.tipo == 12 && ecdhe) {
                    val mensagem = dados.corpo
                    // RFC 5489: a dica de identidade vem antes dos parâmetros.
                    val dica = ((mensagem[0].toInt() and 0xFF) shl 8) or (mensagem[1].toInt() and 0xFF)
                    val base = 2 + dica
                    require(base + 4 < mensagem.size && mensagem[base] == 0x03.toByte()) {
                        "parâmetros da curva inesperados: " + mensagem.take(8).joinToString("") { "%02x".format(it) }
                    }
                    val curva = ((mensagem[base + 1].toInt() and 0xFF) shl 8) or (mensagem[base + 2].toInt() and 0xFF)
                    require(curva == 0x0017) { "curva inesperada: $curva" }
                    val tamPonto = mensagem[base + 3].toInt() and 0xFF
                    val ponto = mensagem.copyOfRange(base + 4, base + 4 + tamPonto)
                    require(ponto.size == 65 && ponto[0] == 0x04.toByte()) { "ponto inesperado" }
                    pontoServidor = ECPoint(BigInteger(1, ponto.copyOfRange(1, 33)), BigInteger(1, ponto.copyOfRange(33, 65)))
                }
                if (dados.tipo == 14) break
            }

            // ClientKeyExchange: identidade e, nas cifras ECDHE, a chave pública
            val identidadeBytes = PSK_ID.toByteArray()
            var compartilhado: ByteArray? = null
            val cke = ByteArrayOutputStream()
            cke.write((identidadeBytes.size ushr 8) and 0xFF)
            cke.write(identidadeBytes.size and 0xFF)
            cke.write(identidadeBytes)
            if (ecdhe) {
                val ponto = pontoServidor ?: error("sem chave do servidor")
                val gerador = KeyPairGenerator.getInstance("EC")
                gerador.initialize(ECGenParameterSpec("secp256r1"))
                val nosso = gerador.generateKeyPair()
                val publico = (nosso.public as java.security.interfaces.ECPublicKey).w
                val nossoPonto = byteArrayOf(0x04) + para32(publico.affineX) + para32(publico.affineY)
                cke.write(nossoPonto.size)
                cke.write(nossoPonto)

                val parametros = AlgorithmParameters.getInstance("EC")
                parametros.init(ECGenParameterSpec("secp256r1"))
                val spec = parametros.getParameterSpec(ECParameterSpec::class.java)
                val chavePublica = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ponto, spec))
                val acordo = KeyAgreement.getInstance("ECDH")
                acordo.init(nosso.private)
                acordo.doPhase(chavePublica, true)
                compartilhado = para32(BigInteger(1, acordo.generateSecret()))
            }
            enviaHandshake(16, cke.toByteArray())

            // Pré-mestre: RFC 5489 (ECDHE) ou RFC 4279 (PSK pura)
            val psk = PSK.toByteArray()
            val preMestra = ByteArrayOutputStream().apply {
                if (compartilhado != null) {
                    // RFC 5489: other_secret = Z (o segredo do ECDH).
                    write((compartilhado.size ushr 8) and 0xFF); write(compartilhado.size and 0xFF); write(compartilhado)
                } else {
                    // RFC 4279: na PSK pura, other_secret são zeros do mesmo
                    // tamanho da chave.
                    write((psk.size ushr 8) and 0xFF); write(psk.size and 0xFF); write(ByteArray(psk.size))
                }
                write((psk.size ushr 8) and 0xFF); write(psk.size and 0xFF); write(psk)
            }.toByteArray()

            val resumo = MessageDigest.getInstance("SHA-256").digest(historico.toByteArray())
            val sementeMestra = if (estendido) {
                "extended master secret".toByteArray() + resumo
            } else {
                "master secret".toByteArray() + clienteAleatorio + servidorAleatorio
            }
            val mestra = prf(preMestra, sementeMestra, 48)
            val tamanhoMaterial = if (gcm) 16 + 16 + 4 + 4 else tamanhoMac * 2 + 16 + 16
            val material = prf(mestra, "key expansion".toByteArray() + servidorAleatorio + clienteAleatorio, tamanhoMaterial)
            if (gcm) {
                chaveCliente = material.copyOfRange(0, 16)
                chaveServidor = material.copyOfRange(16, 32)
                ivCliente = material.copyOfRange(32, 36)
                ivServidor = material.copyOfRange(36, 40)
            } else {
                macCliente = material.copyOfRange(0, tamanhoMac)
                macServidor = material.copyOfRange(tamanhoMac, tamanhoMac * 2)
                chaveCliente = material.copyOfRange(tamanhoMac * 2, tamanhoMac * 2 + 16)
                chaveServidor = material.copyOfRange(tamanhoMac * 2 + 16, tamanhoMac * 2 + 32)
            }

            // ChangeCipherSpec + Finished
            enviaRegistro(20, byteArrayOf(1))
            val verificado = prf(mestra, "client finished".toByteArray() + resumo, 12)
            val nossoFinished = mensagem(20, verificado)
            enviaRegistroCifrado(22, nossoFinished)
            historico.write(nossoFinished)

            // ChangeCipherSpec + Finished do servidor (conferido)
            val (tipoCcs, corpoCcs) = leRegistro()
            require(tipoCcs == 20) {
                "sem troca de cifra: tipo $tipoCcs bytes " + corpoCcs.take(8).joinToString("") { "%02x".format(it) }
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
            val registro: ByteArray
            if (gcm) {
                val explicito = ByteArray(8)
                var s = seqCliente
                for (i in 7 downTo 0) { explicito[i] = (s and 0xFF).toByte(); s = s ushr 8 }
                val cifra = Cipher.getInstance("AES/GCM/NoPadding")
                cifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chaveCliente, "AES"), GCMParameterSpec(128, ivCliente + explicito))
                cifra.updateAAD(cabecalhoMac(seqCliente, tipo, dados.size))
                registro = explicito + cifra.doFinal(dados)
            } else {
                val mac = Mac.getInstance(algoritmoMac)
                mac.init(SecretKeySpec(macCliente, algoritmoMac))
                mac.update(cabecalhoMac(seqCliente, tipo, dados.size))
                mac.update(dados)
                val comMac = dados + mac.doFinal()
                // Preenchimento do TLS: "padding_length" bytes com esse valor,
                // mais um byte final igual a ele (RFC 5246, 6.2.3.2).
                val total = comMac.size
                val folga = (16 - ((total + 1) % 16)) % 16
                val preenchido = comMac + ByteArray(folga + 1) { folga.toByte() }
                val iv = ByteArray(16).also { aleatorio.nextBytes(it) }
                val cifra = Cipher.getInstance("AES/CBC/NoPadding")
                cifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chaveCliente, "AES"), IvParameterSpec(iv))
                registro = iv + cifra.doFinal(preenchido)
            }
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
            return tipo to leBytes(tamanho)
        }

        private fun leRegistroCifrado(): Pair<Int, ByteArray> {
            val (tipo, registro) = leRegistro()
            if (tipo == 20) return tipo to ByteArray(0)
            val dados: ByteArray
            if (gcm) {
                require(registro.size > 24) { "registro curto" }
                val explicito = registro.copyOfRange(0, 8)
                val corpo = registro.copyOfRange(8, registro.size)
                val claro = corpo.size - 16
                val cifra = Cipher.getInstance("AES/GCM/NoPadding")
                cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(chaveServidor, "AES"), GCMParameterSpec(128, ivServidor + explicito))
                cifra.updateAAD(cabecalhoMac(seqServidor, tipo, claro))
                dados = cifra.doFinal(corpo)
            } else {
                require(registro.size > 32) { "registro curto" }
                val iv = registro.copyOfRange(0, 16)
                val cifra = Cipher.getInstance("AES/CBC/NoPadding")
                cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(chaveServidor, "AES"), IvParameterSpec(iv))
                val aberto = cifra.doFinal(registro.copyOfRange(16, registro.size))
                val preenchimento = aberto.last().toInt() and 0xFF
                require(preenchimento + 1 <= aberto.size) { "preenchimento inválido" }
                val comMac = aberto.copyOfRange(0, aberto.size - (preenchimento + 1))
                dados = comMac.copyOfRange(0, comMac.size - tamanhoMac)
            }
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
