package com.ferforastieri.valkyris.feature.lights

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Local pairing in EZ mode (also called SmartConfig): the lamp blinks fast, its
 * Wi-Fi chip listens to the air, and the credentials travel encoded in the size
 * of the packets we send. Nothing here talks to a cloud service or to any vendor
 * application: the phone sends the network name, the password and the one-time
 * values the backend generated for this pairing window.
 *
 * The format is the one documented by the community from the vendor
 * application's own traffic (broadcast and multicast strategies). Two carriers
 * are sent because lamps listen to both:
 *
 *  - multicast: each byte becomes an address 226.<sequence>.<low>.<high> ... in
 *    practice 226.<sequence>.<secondByte>.<firstByte>, sent to port 30012;
 *  - broadcast: the same data grouped in four byte blocks whose length goes to
 *    port 30011, with a CRC-8 header so the lamp can trust the stream.
 */
internal object TuyaSmartConfig {
    const val BROADCAST_PORT = 30011
    const val MULTICAST_PORT = 30012
    private const val MULTICAST_BASE = "226"

    /** Fixed key the vendor application uses for the password field only. */
    private const val PASSWORD_KEY = "a3c6794oiu876t54"

    fun broadcastHead(): List<Int> = listOf(1, 3, 6, 10)

    fun multicastHead(): List<String> = bytesToAddresses("TYST01".map { it.code }, 120)

    /** Length-encoded body for the broadcast carrier. */
    fun broadcastBody(password: String, ssid: String, tokenGroup: String): List<Int> {
        val data = mutableListOf<Int>()
        data += password.length
        data += password.map { it.code }
        data += tokenGroup.length
        data += tokenGroup.map { it.code }
        data += ssid.map { it.code }

        val encoded = mutableListOf<Int>()
        val length = data.size
        val lengthCrc = crc8(listOf(length))
        encoded += (length shr 4) or 16
        encoded += (length and 0xF) or 32
        encoded += (lengthCrc shr 4) or 48
        encoded += (lengthCrc and 0xF) or 64

        var sequence = 0
        var index = 0
        var lastGroupStart = 0
        while (index < length) {
            lastGroupStart = index
            val group = mutableListOf(sequence)
            for (offset in 0 until 4) {
                group += data.getOrElse(index + offset) { 0 }
            }
            val groupCrc = crc8(group)
            encoded += (groupCrc and 0x7F) or 128
            encoded += sequence or 128
            // Only the bytes that exist are emitted: the lamp counts them.
            for (offset in 0 until 4) {
                val value = data.getOrNull(index + offset) ?: break
                encoded += value or 256
            }
            sequence += 1
            index += 4
        }
        // The tail marks the end of the stream for the lamp.
        repeat(length - lastGroupStart) { encoded += 256 }
        return encoded
    }

    /** Address-encoded body for the multicast carrier. */
    fun multicastBody(password: String, ssid: String, tokenGroup: String): List<String> =
        bytesToAddresses(encodePlain(ssid), 64) +
            bytesToAddresses(encodePassword(password), 0) +
            bytesToAddresses(encodePlain(tokenGroup), 32)

    /** tokenGroup is region + token + secret, the values the lamp stores. */
    fun tokenGroup(region: String, token: String, secret: String): String = region + token + secret

    private fun encodePlain(value: String): List<Int> {
        val bytes = value.map { it.code }
        val crc = crc32(bytes)
        return listOf(value.length, value.length) + crc.toLittleEndian() + bytes
    }

    private fun encodePassword(password: String): List<Int> {
        val bytes = password.map { it.code }
        val crc = crc32(bytes)
        val encrypted = encryptPassword(password).map { it.toInt() and 0xFF }
        return listOf(password.length, password.length) + crc.toLittleEndian() + encrypted
    }

    private fun Long.toLittleEndian(): List<Int> = (0 until 4).map { shift -> ((this shr (shift * 8)) and 0xFF).toInt() }

    private fun bytesToAddresses(data: List<Int>, startSequence: Int): List<String> {
        val padded = data.toMutableList()
        if (padded.size % 2 != 0) padded += 0
        val addresses = mutableListOf<String>()
        var sequence = startSequence
        var index = 0
        while (index < padded.size) {
            addresses += "$MULTICAST_BASE.$sequence.${padded[index + 1]}.${padded[index]}"
            sequence += 1
            index += 2
        }
        return addresses
    }

    /** AES-128-ECB with zero padding, exactly like the vendor application. */
    internal fun encryptPassword(password: String): ByteArray {
        val raw = password.toByteArray()
        val padded = ByteArray(((raw.size + 15) / 16) * 16)
        raw.copyInto(padded)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(PASSWORD_KEY.toByteArray(), "AES"))
        return cipher.doFinal(padded)
    }

    private val crc8Table = IntArray(256) { value ->
        var crc = value
        repeat(8) {
            crc = if (crc and 1 != 0) (crc shr 1) xor 0x8C else crc shr 1
        }
        crc and 0xFF
    }

    internal fun crc8(values: List<Int>): Int {
        var crc = 0
        values.forEach { value -> crc = crc8Table[(crc xor value) and 0xFF] }
        return crc
    }

    private val crc32Table = LongArray(256) { index ->
        var crc = index.toLong()
        repeat(8) { crc = if (crc and 1L != 0L) (crc shr 1) xor 0xEDB88320L else crc shr 1 }
        crc and 0xFFFFFFFFL
    }

    internal fun crc32(values: List<Int>): Long {
        var crc = 0xFFFFFFFFL
        values.forEach { value -> crc = (crc shr 8) xor crc32Table[((crc xor value.toLong()) and 0xFF).toInt()] }
        return crc xor 0xFFFFFFFFL
    }
}

/**
 * Sends the pairing stream over the air for as long as the pairing window is
 * open. The phone must be connected to the 2.4 GHz network the lamp will join.
 */
internal class LocalPairingSender(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * @param durationMillis por quanto tempo insistir; zero significa até o
     *   chamador cancelar, que é como a tela mantém o envio durante toda a
     *   janela de pareamento.
     */
    suspend fun send(
        ssid: String,
        password: String,
        tokenGroup: String,
        durationMillis: Long = 0,
        onRound: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val multicastLock = wifi?.createMulticastLock("valkyris-pairing")?.apply { setReferenceCounted(false); acquire() }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val broadcast = InetAddress.getByName("255.255.255.255")
            val broadcastHead = TuyaSmartConfig.broadcastHead()
            val broadcastBody = TuyaSmartConfig.broadcastBody(password, ssid, tokenGroup)
            val multicastHead = TuyaSmartConfig.multicastHead().map(InetAddress::getByName)
            val multicastBody = TuyaSmartConfig.multicastBody(password, ssid, tokenGroup).map(InetAddress::getByName)
            val deadline = if (durationMillis > 0) System.currentTimeMillis() + durationMillis else Long.MAX_VALUE
            var round = 0
            while (System.currentTimeMillis() < deadline) {
                round += 1
                onRound(round)
                // The lamp needs the header several times before the data.
                repeat(20) {
                    multicastHead.forEach { socket.sendAddress(it, TuyaSmartConfig.MULTICAST_PORT) }
                    broadcastHead.forEach { socket.sendLength(it, broadcast, TuyaSmartConfig.BROADCAST_PORT) }
                }
                repeat(6) {
                    multicastHead.forEach { socket.sendAddress(it, TuyaSmartConfig.MULTICAST_PORT) }
                    multicastBody.forEach { socket.sendAddress(it, TuyaSmartConfig.MULTICAST_PORT) }
                    broadcastBody.forEach { socket.sendLength(it, broadcast, TuyaSmartConfig.BROADCAST_PORT) }
                }
                delay(1500)
            }
        }
        multicastLock?.let { if (it.isHeld) it.release() }
    }

    private fun DatagramSocket.sendAddress(address: InetAddress, port: Int) {
        send(DatagramPacket(ByteArray(1), 1, address, port))
    }

    /** The size of the packet is the data: it carries up to 511 bytes. */
    private fun DatagramSocket.sendLength(length: Int, address: InetAddress, port: Int) {
        if (length <= 0) return
        send(DatagramPacket(ByteArray(length), length, address, port))
    }
}
