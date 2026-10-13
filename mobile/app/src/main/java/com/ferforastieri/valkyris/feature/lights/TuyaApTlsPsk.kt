package com.ferforastieri.valkyris.feature.lights

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * O pareamento do aparelho, exatamente como o firmware do fabricante espera
 * (código aberto do TuyaOpen — `ap_netcfg.c` e `tuya_protocol.c`):
 *
 *  - conexão TLS 1.2 com chave pré-compartilhada (PSK) na porta 6668, chave
 *    "123456" e identidade "psk_identity";
 *  - por dentro dela, um quadro LPV3.5: cabeçalho 00 00 66 99, tipo 0x14
 *    (configuração de Wi-Fi), conteúdo em AES-128-GCM com a chave fixa
 *    MD5("OXLvslCvUxcTPLGO") e cauda 00 00 99 66;
 *  - o conteúdo é o JSON com "ssid", "passwd", "token" e "reg";
 *  - o aparelho responde com um quadro do mesmo tipo e um código (0 = aceito).
 *
 * O Android não expõe TLS-PSK na API pública (o aplicativo oficial usa código
 * nativo), por isso o diálogo é feito aqui com a criptografia do próprio Java.
 */
internal object TuyaApTlsPsk {
    private const val PORTA = 6668
    private const val PSK = "123456"
    private const val PSK_ID = "psk_identity"
    private const val CIFRA_PSK_GCM = 0x00A8 // TLS_PSK_WITH_AES_128_GCM_SHA256

    private val aleatorio = SecureRandom()

    /**
     * Conecta, faz o TLS-PSK e envia os JSONs em ordem até o aparelho aceitar.
     * Devolve o nome do formato aceito, ou nulo.
     */
    fun entrega(
        network: android.net.Network,
        endereco: String,
        corpos: List<Pair<String, String>>,
        onAttempt: (String) -> Unit,
    ): String? = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(endereco, PORTA), 2_500)
            socket.soTimeout = 4_000
            val tls = Handshake(socket.getInputStream(), socket.getOutputStream())
            tls.abre()
            // Primeiro com o cabeçalho de 4 bytes (como o aparelho monta),
            // depois só o JSON.
            for (comCabecalho in listOf(true, false)) {
                for ((nome, corpo) in corpos) {
                    onAttempt(if (comCabecalho) nome else "$nome-puro")
                    val resposta = tls.envia(quadro(0x14, corpo, comCabecalho)) ?: continue
                    if (aceito(resposta)) return@use "tls-psk"
                }
            }
            null
        }
    }.getOrNull()

    private fun aceito(resposta: ByteArray): Boolean {
        val (tipo, conteudo) = lerQuadro(resposta) ?: return false
        if (tipo != 0x14 || conteudo.size < 4) return false
        val codigo = ((conteudo[0].toInt() and 0xFF) shl 24) or ((conteudo[1].toInt() and 0xFF) shl 16) or
            ((conteudo[2].toInt() and 0xFF) shl 8) or (conteudo[3].toInt() and 0xFF)
        return codigo == 0
    }

    // ------------------------------------------------------------------ quadro LPV3.5

    private val CHAVE: ByteArray = MessageDigest.getInstance("MD5").digest("OXLvslCvUxcTPLGO".toByteArray())
    private val CABECA = byteArrayOf(0x00, 0x00, 0x66, 0x99.toByte())
    private val CAUDA = byteArrayOf(0x00, 0x00, 0x99.toByte(), 0x66)

    fun quadro(tipo: Int, json: String, comCabecalho: Boolean): ByteArray {
        val conteudo = if (comCabecalho) ByteArray(4) + json.toByteArray() else json.toByteArray()
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

    fun lerQuadro(bytes: ByteArray): Pair<Int, ByteArray>? = runCatching {
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

    /** Diálogo TLS 1.2 somente com chave pré-compartilhada (sem certificados). */
    private class Handshake(private val entrada: InputStream, private val saida: java.io.OutputStream) {
        private val clienteAleatorio = ByteArray(32).also { aleatorio.nextBytes(it) }
        private val historico = ByteArrayOutputStream()
        private var chaveCliente = ByteArray(16)
        private var ivCliente = ByteArray(4)
        private var chaveServidor = ByteArray(16)
        private var ivServidor = ByteArray(4)
        private var seqCliente = 0L
        private var seqServidor = 0L

        fun abre() {
            // ClientHello
            val corpo = ByteArrayOutputStream()
            corpo.write(byteArrayOf(0x03, 0x03)) // TLS 1.2
            corpo.write(clienteAleatorio)
            corpo.write(0) // sem sessão
            corpo.write(byteArrayOf(0x00, 0x02)) // uma cifra
            corpo.write(byteArrayOf((CIFRA_PSK_GCM ushr 8).toByte(), (CIFRA_PSK_GCM and 0xFF).toByte()))
            corpo.write(byteArrayOf(0x01, 0x00)) // sem compressão
            enviaHandshake(1, corpo.toByteArray())

            // ServerHello (e o que vier até o ServerHelloDone)
            var servidorAleatorio = ByteArray(32)
            var terminado = false
            while (!terminado) {
                val (tipo, dados) = leRegistro()
                if (tipo != 22) continue
                var p = 0
                while (p + 4 <= dados.size) {
                    val tipoMsg = dados[p].toInt() and 0xFF
                    val tamanho = ((dados[p + 1].toInt() and 0xFF) shl 16) or ((dados[p + 2].toInt() and 0xFF) shl 8) or (dados[p + 3].toInt() and 0xFF)
                    if (p + 4 + tamanho > dados.size) break
                    val mensagem = dados.copyOfRange(p, p + 4 + tamanho)
                    historico.write(mensagem)
                    if (tipoMsg == 2 && mensagem.size >= 38) {
                        servidorAleatorio = mensagem.copyOfRange(6, 38)
                    }
                    if (tipoMsg == 14) terminado = true // ServerHelloDone
                    p += 4 + tamanho
                }
            }

            // ClientKeyExchange: identidade + chave
            val cke = ByteArrayOutputStream()
            val id = PSK_ID.toByteArray()
            val chave = PSK.toByteArray()
            cke.write((id.size ushr 8) and 0xFF); cke.write(id.size and 0xFF); cke.write(id)
            cke.write((chave.size ushr 8) and 0xFF); cke.write(chave.size and 0xFF); cke.write(chave)
            enviaHandshake(16, cke.toByteArray())

            // Chaves: pré-mestra = chave + identidade (RFC 4279)
            val preMestra = ByteArrayOutputStream().apply {
                write((chave.size ushr 8) and 0xFF); write(chave.size and 0xFF); write(chave)
                write((id.size ushr 8) and 0xFF); write(id.size and 0xFF); write(id)
            }.toByteArray()
            val mestra = prf(preMestra, "master secret".toByteArray() + clienteAleatorio + servidorAleatorio, 48)
            val material = prf(mestra, "key expansion".toByteArray() + servidorAleatorio + clienteAleatorio, 16 + 16 + 4 + 4)
            chaveCliente = material.copyOfRange(0, 16)
            chaveServidor = material.copyOfRange(16, 32)
            ivCliente = material.copyOfRange(32, 36)
            ivServidor = material.copyOfRange(36, 40)

            // ChangeCipherSpec + Finished
            enviaRegistro(20, byteArrayOf(1))
            val resumo = MessageDigest.getInstance("SHA-256").digest(historico.toByteArray())
            val verificado = prf(mestra, "client finished".toByteArray() + resumo, 12)
            enviaRegistroCifrado(22, mensagem(20, verificado))

            // ChangeCipherSpec e Finished do servidor
            val (tipoCcs, _) = leRegistro()
            if (tipoCcs == 20) leRegistroCifrado()
        }

        /** Manda dados de aplicação (o quadro) e devolve a próxima resposta. */
        fun envia(dados: ByteArray): ByteArray? {
            enviaRegistroCifrado(23, dados)
            repeat(4) {
                val (tipo, conteudo) = leRegistroCifrado()
                if (tipo == 23) return conteudo
            }
            return null
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
            val explicito = ByteArray(8)
            var s = seqCliente
            for (i in 7 downTo 0) { explicito[i] = (s and 0xFF).toByte(); s = s ushr 8 }
            val cifra = Cipher.getInstance("AES/GCM/NoPadding")
            cifra.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chaveCliente, "AES"), GCMParameterSpec(128, ivCliente + explicito))
            cifra.updateAAD(adicionais(seqCliente, tipo, dados.size))
            val saida = explicito + cifra.doFinal(dados)
            val cabecalho = byteArrayOf(tipo.toByte(), 0x03, 0x03, ((saida.size ushr 8) and 0xFF).toByte(), (saida.size and 0xFF).toByte())
            this.saida.write(cabecalho)
            this.saida.write(saida)
            this.saida.flush()
            seqCliente++
        }

        private fun leRegistro(): Pair<Int, ByteArray> {
            val cabecalho = leBytes(5)
            val tipo = cabecalho[0].toInt() and 0xFF
            val tamanho = ((cabecalho[3].toInt() and 0xFF) shl 8) or (cabecalho[4].toInt() and 0xFF)
            return tipo to leBytes(tamanho)
        }

        private fun leRegistroCifrado(): Pair<Int, ByteArray> {
            val cabecalho = leBytes(5)
            val tipo = cabecalho[0].toInt() and 0xFF
            val tamanho = ((cabecalho[3].toInt() and 0xFF) shl 8) or (cabecalho[4].toInt() and 0xFF)
            val registro = leBytes(tamanho)
            if (tipo == 20) return tipo to ByteArray(0) // ChangeCipherSpec
            val explicito = registro.copyOfRange(0, 8)
            val corpo = registro.copyOfRange(8, registro.size)
            val claro = corpo.size - 16
            val cifra = Cipher.getInstance("AES/GCM/NoPadding")
            cifra.init(Cipher.DECRYPT_MODE, SecretKeySpec(chaveServidor, "AES"), GCMParameterSpec(128, ivServidor + explicito))
            cifra.updateAAD(adicionais(seqServidor, tipo, claro))
            val dados = cifra.doFinal(corpo)
            seqServidor++
            return tipo to dados
        }

        private fun adicionais(sequencia: Long, tipo: Int, tamanho: Int): ByteArray {
            val aad = ByteArray(13)
            var s = sequencia
            for (i in 7 downTo 0) { aad[i] = (s and 0xFF).toByte(); s = s ushr 8 }
            aad[8] = tipo.toByte()
            aad[9] = 0x03; aad[10] = 0x03
            aad[11] = ((tamanho ushr 8) and 0xFF).toByte()
            aad[12] = (tamanho and 0xFF).toByte()
            return aad
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
    }
}
