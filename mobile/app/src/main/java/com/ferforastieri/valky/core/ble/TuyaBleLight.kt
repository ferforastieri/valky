package com.ferforastieri.valky.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.ferforastieri.valky.core.model.LightBle
import com.ferforastieri.valky.core.model.LightDevice
import com.ferforastieri.valky.core.model.LightDpMapping
import com.ferforastieri.valky.core.model.LightState
import com.ferforastieri.valky.core.model.LightStatePatch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Controle local das lâmpadas Tuya pelo canal BLE. O transporte foi portado do
 * projeto ha_tuya_ble (MIT), que documenta o handshake e os quadros usados pelo
 * aplicativo oficial; o Valky usa os mesmos data points do canal Wi-Fi.
 *
 * O BLE só entra quando o comando pelo backend falha ou a lâmpada está sem IP.
 * O celular precisa estar perto e ter a permissão de Bluetooth do Android.
 */
@SuppressLint("MissingPermission")
class TuyaBleLight(private val context: Context) {

    suspend fun control(light: LightDevice, credentials: LightBle, patch: LightStatePatch): LightState =
        withContext(Dispatchers.IO) {
            if (credentials.localKey.length < 6) error("Chave local inválida para o Bluetooth")
            val datapoints = buildDatapoints(credentials.dpMapping, light.state, patch)
            if (datapoints.isEmpty()) return@withContext applyPatch(light.state, patch)

            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: error("Bluetooth indisponível neste aparelho")
            val adapter = manager.adapter ?: error("Bluetooth indisponível neste aparelho")
            if (!adapter.isEnabled) error("Ligue o Bluetooth para controlar a lâmpada")
            requirePermissions()

            var lastError: Throwable? = null
            for (result in scan(adapter)) {
                val uuid = parseUuid(result) ?: continue
                val session = TuyaBleSession(
                    context = context,
                    device = result.device,
                    uuid = uuid,
                    deviceId = credentials.deviceId,
                    localKey = credentials.localKey,
                )
                try {
                    withTimeout(12_000) { session.connect() }
                    withTimeout(15_000) { session.handshake() }
                    session.sendDatapoints(datapoints)
                    delay(250)
                    return@withContext applyPatch(light.state, patch)
                } catch (error: Throwable) {
                    lastError = error
                } finally {
                    session.close()
                }
            }
            throw lastError ?: IllegalStateException("Nenhuma lâmpada Tuya por perto no Bluetooth")
        }

    private fun requirePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        if (scan != PackageManager.PERMISSION_GRANTED || connect != PackageManager.PERMISSION_GRANTED) {
            error("Permissão de Bluetooth necessária para controlar a lâmpada")
        }
    }

    private suspend fun scan(adapter: BluetoothAdapter): List<ScanResult> {
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isTuya(result)) return
                synchronized(found) { found[result.device.address] = result }
            }
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                scanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = scope.launch {
                delay(SCAN_MILLIS)
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) {
                    continuation.resume(synchronized(found) { found.values.toList() })
                }
                scope.cancel()
            }
            continuation.invokeOnCancellation {
                runCatching { scanner.stopScan(callback) }
                job.cancel()
                scope.cancel()
            }
        }
    }

    private fun isTuya(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        if (record.getManufacturerSpecificData(TuyaBleProtocol.MANUFACTURER_ID) != null) return true
        if (record.getServiceData(ParcelUuid(TuyaBleProtocol.SERVICE_A201)) != null) return true
        if (record.getServiceData(ParcelUuid(TuyaBleProtocol.SERVICE_FD50)) != null) return true
        return record.serviceUuids?.any {
            it.uuid == TuyaBleProtocol.SERVICE_A201 || it.uuid == TuyaBleProtocol.SERVICE_FD50
        } == true
    }

    private fun parseUuid(result: ScanResult): String? {
        val record = result.scanRecord ?: return null
        val manufacturer = record.getManufacturerSpecificData(TuyaBleProtocol.MANUFACTURER_ID) ?: return null
        if (manufacturer.size <= 6) return null
        val rawUuid = manufacturer.copyOfRange(6, manufacturer.size)
        val service = record.getServiceData(ParcelUuid(TuyaBleProtocol.SERVICE_A201))
        val product = if (service != null && service.size > 1 && service[0].toInt() == 0) {
            service.copyOfRange(1, service.size)
        } else {
            null
        }
        val uuidBytes = if (product != null && product.isNotEmpty() && rawUuid.size % 16 == 0) {
            val key = TuyaBleProtocol.md5(product)
            TuyaBleProtocol.aes(false, key, key, rawUuid)
        } else {
            rawUuid
        }
        return uuidBytes.take(16).toByteArray().toString(Charsets.UTF_8).trim('\u0000')
    }

    private fun buildDatapoints(mapping: LightDpMapping, current: LightState, patch: LightStatePatch): List<TuyaBleDp> {
        val byId = LinkedHashMap<Int, TuyaBleDp>()
        fun add(dpId: String?, type: Int, value: ByteArray) {
            val id = dpId?.toIntOrNull() ?: return
            byId[id] = TuyaBleDp(id, type, value)
        }
        if (patch.power != null) {
            add(mapping.switchDp, TuyaBleProtocol.TYPE_BOOL, byteArrayOf(if (patch.power) 1 else 0))
        }
        if (patch.brightness != null) {
            val raw = TuyaBleProtocol.percentToRaw(patch.brightness, mapping.brightnessMin, mapping.brightnessMax)
            add(mapping.brightness, TuyaBleProtocol.TYPE_VALUE, TuyaBleProtocol.int4(raw))
        }
        if (patch.temperatureKelvin != null) {
            val raw = TuyaBleProtocol.kelvinToRaw(patch.temperatureKelvin, mapping.temperatureMin, mapping.temperatureMax)
            add(mapping.temperature, TuyaBleProtocol.TYPE_VALUE, TuyaBleProtocol.int4(raw))
            add(mapping.mode, TuyaBleProtocol.TYPE_ENUM, byteArrayOf(0))
        }
        patch.color?.let { colour ->
            add(mapping.colour, TuyaBleProtocol.TYPE_STRING, TuyaBleProtocol.encodeColour(colour, mapping.colourFormat).toByteArray(Charsets.US_ASCII))
            add(mapping.mode, TuyaBleProtocol.TYPE_ENUM, byteArrayOf(1))
        }
        if (patch.mode != null) {
            val colour = patch.mode.equals("color", true) || patch.mode.equals("colour", true)
            add(mapping.mode, TuyaBleProtocol.TYPE_ENUM, byteArrayOf(if (colour) 1 else 0))
        }
        return byId.values.toList()
    }

    private fun applyPatch(state: LightState, patch: LightStatePatch): LightState = state.copy(
        online = true,
        power = patch.power ?: state.power,
        mode = patch.mode ?: when {
            patch.color != null -> "color"
            patch.temperatureKelvin != null -> "white"
            else -> state.mode
        },
        brightness = patch.brightness ?: state.brightness,
        temperatureKelvin = patch.temperatureKelvin ?: state.temperatureKelvin,
        color = patch.color ?: state.color,
        updatedAt = Instant.now().toString(),
    )

    private companion object {
        const val SCAN_MILLIS = 6_000L
    }
}

internal data class TuyaBleDp(val id: Int, val type: Int, val value: ByteArray)

/** Constantes e conversões do protocolo Tuya BLE (mesmas do canal Wi-Fi). */
internal object TuyaBleProtocol {
    const val MANUFACTURER_ID = 0x07D0
    const val TYPE_BOOL = 1
    const val TYPE_VALUE = 2
    const val TYPE_STRING = 3
    const val TYPE_ENUM = 4

    val SERVICE_A201: UUID = UUID.fromString("0000a201-0000-1000-8000-00805f9b34fb")
    val SERVICE_FD50: UUID = UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_A201: UUID = UUID.fromString("00002b10-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_A201: UUID = UUID.fromString("00002b11-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_FD50: UUID = UUID.fromString("00000002-0000-1001-8001-00805f9b07d0")
    val CHAR_WRITE_FD50: UUID = UUID.fromString("00000001-0000-1001-8001-00805f9b07d0")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun percentToRaw(percent: Int, minimum: Int, maximum: Int): Int =
        minimum + ((maximum - minimum) * percent.coerceIn(0, 100) / 100f).roundToInt()

    fun kelvinToRaw(kelvin: Int, minimum: Int, maximum: Int): Int {
        val clamped = kelvin.coerceIn(2700, 6500)
        val ratio = (clamped - 2700) / 3800f
        return minimum + ((maximum - minimum) * ratio).roundToInt()
    }

    fun encodeColour(colour: com.ferforastieri.valky.core.model.LightColor, format: String): String {
        val hue = colour.hue.coerceIn(0, 360)
        val saturation = colour.saturation.coerceIn(0, 100)
        val value = colour.value.coerceIn(0, 100)
        if (format == "rgb8") {
            val (red, green, blue) = hsvToRgb(hue, saturation, value)
            return "%02x%02x%02x".format(red, green, blue)
        }
        val scaledSaturation = (saturation * 10f).roundToInt()
        val scaledValue = (value * 10f).roundToInt()
        return "%04x%04x%04x".format(hue, scaledSaturation, scaledValue)
    }

    private fun hsvToRgb(hue: Int, saturation: Int, value: Int): Triple<Int, Int, Int> {
        if (saturation == 0) {
            val level = value * 255 / 100
            return Triple(level, level, level)
        }
        val h = (hue % 360) / 60f
        val s = saturation / 100f
        val v = value / 100f
        val index = h.toInt() % 6
        val fraction = h - h.toInt()
        val p = v * (1 - s)
        val q = v * (1 - s * fraction)
        val t = v * (1 - s * (1 - fraction))
        val (red, green, blue) = when (index) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Triple((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
    }

    fun int4(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    fun md5(raw: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(raw)

    fun aes(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val mode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun packInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var current = value
        while (true) {
            var byte = current and 0x7F
            current = current ushr 7
            if (current != 0) byte = byte or 0x80
            out.write(byte)
            if (current == 0) break
        }
        return out.toByteArray()
    }

    fun unpackInt(data: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var offset = 0
        while (offset < 5) {
            val pos = start + offset
            if (pos >= data.size) error("Quadro BLE incompleto")
            val byte = data[pos].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl (offset * 7))
            offset++
            if (byte and 0x80 == 0) break
        }
        return result to (start + offset)
    }
}

@SuppressLint("MissingPermission")
private class TuyaBleSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val uuid: String,
    private val deviceId: String,
    private val localKey: String,
) {
    private var gatt: BluetoothGatt? = null
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private var writeWaiter: CompletableDeferred<Unit>? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var protocolVersion = 2
    private var sequence = 1
    private var sessionKey: ByteArray = ByteArray(0)
    private val loginMaterial = localKey.take(6).toByteArray(Charsets.US_ASCII)
    private val loginKey = TuyaBleProtocol.md5(loginMaterial)

    suspend fun connect() {
        suspendCancellableCoroutine { continuation ->
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Falha ao conectar no Bluetooth"))
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Serviços BLE indisponíveis"))
                        return
                    }
                    val service = gatt.getService(TuyaBleProtocol.SERVICE_A201)
                        ?: gatt.getService(TuyaBleProtocol.SERVICE_FD50)
                    val notify = gatt.getService(TuyaBleProtocol.SERVICE_A201)?.getCharacteristic(TuyaBleProtocol.CHAR_NOTIFY_A201)
                        ?: gatt.getService(TuyaBleProtocol.SERVICE_FD50)?.getCharacteristic(TuyaBleProtocol.CHAR_NOTIFY_FD50)
                        ?: service?.characteristics?.firstOrNull {
                            it.uuid == TuyaBleProtocol.CHAR_NOTIFY_A201 || it.uuid == TuyaBleProtocol.CHAR_NOTIFY_FD50
                        }
                    val write = gatt.getService(TuyaBleProtocol.SERVICE_A201)?.getCharacteristic(TuyaBleProtocol.CHAR_WRITE_A201)
                        ?: gatt.getService(TuyaBleProtocol.SERVICE_FD50)?.getCharacteristic(TuyaBleProtocol.CHAR_WRITE_FD50)
                        ?: service?.characteristics?.firstOrNull {
                            it.uuid == TuyaBleProtocol.CHAR_WRITE_A201 || it.uuid == TuyaBleProtocol.CHAR_WRITE_FD50
                        }
                    if (notify == null || write == null) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Canal BLE da Tuya não encontrado"))
                        return
                    }
                    writeCharacteristic = write
                    gatt.setCharacteristicNotification(notify, true)
                    val descriptor = notify.getDescriptor(TuyaBleProtocol.CCCD)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Notificações BLE recusadas"))
                        }
                    } else if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (!continuation.isActive) return
                    if (status == BluetoothGatt.GATT_SUCCESS) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException("Notificações BLE recusadas"))
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    val waiter = writeWaiter
                    writeWaiter = null
                    if (status == BluetoothGatt.GATT_SUCCESS) waiter?.complete(Unit)
                    else waiter?.completeExceptionally(IllegalStateException("Escrita BLE recusada"))
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    notifications.trySend(value.copyOf())
                }

                @Deprecated("Compatibilidade com Android antigo")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    notifications.trySend((characteristic.value ?: ByteArray(0)).copyOf())
                }
            }
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            gatt = created
            if (created == null && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Não foi possível abrir o GATT"))
            }
            continuation.invokeOnCancellation {
                runCatching { created?.disconnect() }
                runCatching { created?.close() }
            }
        }
    }

    suspend fun handshake() {
        val (_, info) = send(CODE_DEVICE_INFO, ByteArray(0), wait = true)
        if (info.size < 46) error("Resposta de identificação BLE inválida")
        protocolVersion = info[2].toInt() and 0xFF
        val random = info.copyOfRange(6, 12)
        sessionKey = TuyaBleProtocol.md5(loginMaterial + random)
        val pairing = ByteArray(44)
        uuid.toByteArray(Charsets.UTF_8).copyInto(pairing, 0, 0, minOf(16, uuid.length))
        loginMaterial.copyInto(pairing, 16)
        deviceId.toByteArray(Charsets.UTF_8).copyInto(pairing, 22, 0, minOf(22, deviceId.length))
        val (_, result) = send(CODE_PAIR, pairing, wait = true)
        if (result.isEmpty() || (result[0].toInt() != 0 && result[0].toInt() != 2)) {
            error("A lâmpada recusou a autenticação BLE")
        }
    }

    suspend fun sendDatapoints(datapoints: List<TuyaBleDp>) {
        if (datapoints.isEmpty()) return
        val payload = ByteArrayOutputStream()
        if (protocolVersion >= 4) {
            payload.write(0)
            payload.write(TuyaBleProtocol.int4(sequence))
            for (dp in datapoints) {
                payload.write(dp.id)
                payload.write(dp.type)
                payload.write((dp.value.size ushr 8) and 0xFF)
                payload.write(dp.value.size and 0xFF)
                payload.write(dp.value)
            }
            send(CODE_DPS_V4, payload.toByteArray(), wait = false)
        } else {
            for (dp in datapoints) {
                payload.write(dp.id)
                payload.write(dp.type)
                payload.write(dp.value.size and 0xFF)
                payload.write(dp.value)
            }
            send(CODE_DPS, payload.toByteArray(), wait = false)
        }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private suspend fun send(code: Int, data: ByteArray, wait: Boolean): Pair<Int, ByteArray> {
        val seq = sequence++
        val encryptKey = if (code == CODE_DEVICE_INFO) loginKey else sessionKey
        val flag = if (code == CODE_DEVICE_INFO) 4 else 5
        val protocol = if (code == CODE_DEVICE_INFO) 2 else protocolVersion
        for (packet in buildPackets(seq, code, data, encryptKey, flag, protocol)) {
            write(packet)
            delay(15)
        }
        if (!wait) return code to ByteArray(0)
        val deadline = System.currentTimeMillis() + RESPONSE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val decoded = decode(readNotification(RESPONSE_MILLIS)) ?: continue
            if (decoded.responseTo == seq) return decoded.code to decoded.data
        }
        error("A lâmpada não respondeu ao Bluetooth")
    }

    private suspend fun write(packet: ByteArray) {
        writeMutex.withLock {
            val characteristic = writeCharacteristic ?: error("Canal BLE sem característica de escrita")
            val waiter = CompletableDeferred<Unit>()
            writeWaiter = waiter
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(characteristic) == true
            }
            if (!started) {
                writeWaiter = null
                error("Não foi possível enviar o comando BLE")
            }
            withTimeout(WRITE_MILLIS) { waiter.await() }
        }
    }

    private suspend fun readNotification(timeoutMillis: Long): ByteArray = withTimeout(timeoutMillis) {
        val buffer = ByteArrayOutputStream()
        var expected = -1
        while (true) {
            val data = notifications.receive()
            val (packetNumber, afterNumber) = TuyaBleProtocol.unpackInt(data, 0)
            var pos = afterNumber
            if (packetNumber == 0) {
                val (length, afterLength) = TuyaBleProtocol.unpackInt(data, pos)
                expected = length
                pos = afterLength + 1
            }
            if (pos > data.size) error("Notificação BLE incompleta")
            buffer.write(data, pos, data.size - pos)
            if (expected in 0..buffer.size()) return@withTimeout buffer.toByteArray()
        }
        @Suppress("UNREACHABLE_CODE")
        error("Notificação BLE incompleta")
    }

    private data class Decoded(val responseTo: Int, val code: Int, val data: ByteArray)

    private fun decode(encrypted: ByteArray): Decoded? {
        if (encrypted.size < 17) return null
        val flag = encrypted[0].toInt() and 0xFF
        val key = when (flag) {
            4, 14 -> loginKey
            5, 15 -> sessionKey
            else -> return null
        }
        if (key.isEmpty()) return null
        val iv = encrypted.copyOfRange(1, 17)
        val ciphertext = encrypted.copyOfRange(17, encrypted.size)
        if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
        val raw = runCatching { TuyaBleProtocol.aes(false, key, iv, ciphertext) }.getOrNull() ?: return null
        if (raw.size < 12) return null
        val responseTo = ByteBuffer.wrap(raw, 4, 4).int
        val code = ByteBuffer.wrap(raw, 8, 2).short.toInt() and 0xFFFF
        val length = ByteBuffer.wrap(raw, 10, 2).short.toInt() and 0xFFFF
        if (length < 0 || 12 + length > raw.size) return null
        val data = raw.copyOfRange(12, 12 + length)
        val crcAt = 12 + length
        if (crcAt + 2 <= raw.size) {
            val expected = ByteBuffer.wrap(raw, crcAt, 2).short.toInt() and 0xFFFF
            if (TuyaBleProtocol.crc16(raw.copyOfRange(0, crcAt)) != expected) return null
        }
        return Decoded(responseTo, code, data)
    }

    private fun buildPackets(seq: Int, code: Int, data: ByteArray, key: ByteArray, flag: Int, protocol: Int): List<ByteArray> {
        val raw = ByteArrayOutputStream()
        raw.write(TuyaBleProtocol.int4(seq))
        raw.write(TuyaBleProtocol.int4(0))
        raw.write((code ushr 8) and 0xFF)
        raw.write(code and 0xFF)
        raw.write((data.size ushr 8) and 0xFF)
        raw.write(data.size and 0xFF)
        raw.write(data)
        val crc = TuyaBleProtocol.crc16(raw.toByteArray())
        raw.write((crc ushr 8) and 0xFF)
        raw.write(crc and 0xFF)
        while (raw.size() % 16 != 0) raw.write(0)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ciphertext = TuyaBleProtocol.aes(true, key, iv, raw.toByteArray())
        val encrypted = ByteArrayOutputStream()
        encrypted.write(flag)
        encrypted.write(iv)
        encrypted.write(ciphertext)
        val body = encrypted.toByteArray()
        val packets = mutableListOf<ByteArray>()
        var pos = 0
        var number = 0
        while (pos < body.size) {
            val header = ByteArrayOutputStream()
            header.write(TuyaBleProtocol.packInt(number))
            if (number == 0) {
                header.write(TuyaBleProtocol.packInt(body.size))
                header.write((protocol shl 4) and 0xFF)
            }
            val chunkSize = (20 - header.size()).coerceAtLeast(1)
            val chunk = body.copyOfRange(pos, minOf(pos + chunkSize, body.size))
            header.write(chunk)
            packets += header.toByteArray()
            pos += chunk.size
            number++
        }
        return packets
    }

    private companion object {
        const val CODE_DEVICE_INFO = 0x0000
        const val CODE_PAIR = 0x0001
        const val CODE_DPS = 0x0002
        const val CODE_DPS_V4 = 0x0027
        const val RESPONSE_MILLIS = 5_000L
        const val WRITE_MILLIS = 3_000L
    }
}
