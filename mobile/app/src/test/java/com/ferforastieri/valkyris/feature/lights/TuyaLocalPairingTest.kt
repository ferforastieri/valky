package com.ferforastieri.valkyris.feature.lights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vetores gerados a partir da referência comunitária que documentou o formato
 * (a mesma que o tuya-convert usa para configurar lâmpadas sem a nuvem). Eles
 * garantem que a codificação do pareamento local não regrida sem uma lâmpada
 * física por perto.
 */
class TuyaLocalPairingTest {
    private val ssid = "CasaBoa"
    private val password = "senha123"
    private val tokenGroup = TuyaSmartConfig.tokenGroup("AY", "AbCdEfGh12345678", "pr_0")

    @Test fun tokenGroupConcatenatesRegionTokenAndSecret() {
        assertEquals("AYAbCdEfGh12345678pr_0", tokenGroup)
    }

    @Test fun multicastHeadIsTheFixedMarkerTheLampListensFor() {
        assertEquals(listOf("226.120.89.84", "226.121.84.83", "226.122.49.48"), TuyaSmartConfig.multicastHead())
    }

    @Test fun broadcastHeadStartsWithTheShortFramesThatOpenTheStream() {
        assertEquals(listOf(1, 3, 6, 10), TuyaSmartConfig.broadcastHead())
    }

    @Test fun broadcastBodyMatchesTheReferenceEncoding() {
        val expected = listOf(
            18, 39, 58, 64, 200, 128, 264, 371, 357, 366, 164, 129, 360, 353, 305, 306, 192, 130,
            307, 278, 321, 345, 129, 131, 321, 354, 323, 356, 216, 132, 325, 358, 327, 360, 163, 133,
            305, 306, 307, 308, 229, 134, 309, 310, 311, 312, 136, 135, 368, 370, 351, 304, 128, 136,
            323, 353, 371, 353, 240, 137, 322, 367, 353, 256, 256, 256,
        )
        assertEquals(expected, TuyaSmartConfig.broadcastBody(password, ssid, tokenGroup))
    }

    @Test fun multicastBodySplitsSsidPasswordAndTokenInOrder() {
        val body = TuyaSmartConfig.multicastBody(password, ssid, tokenGroup)
        assertEquals(listOf("226.64.7.7", "226.65.25.20"), body.take(2))
        // A senha começa na sequência 0, com tamanho e CRC em little endian.
        val passwordStart = body.indexOfFirst { it == "226.0.8.8" }
        assertTrue("a senha deve estar no corpo", passwordStart > 0)
        assertEquals("226.1.82.88", body[passwordStart + 1])
        // O grupo de token começa na sequência 32.
        assertTrue(body.any { it.startsWith("226.32.") })
    }

    @Test fun passwordIsEncryptedWithTheVendorKey() {
        val encrypted = TuyaSmartConfig.encryptPassword("senha123")
        assertEquals(16, encrypted.size)
        // 16 bytes para uma senha de 8 caracteres; o valor vem do vetor de referência.
        val expected = bodyPasswordBlock()
        assertEquals(expected, encrypted.map { it.toInt() and 0xFF })
    }

    // AES-128-ECB com a chave fixa do aplicativo, recalculado a partir da
    // referência comunitária do formato.
    private fun bodyPasswordBlock(): List<Int> = listOf(
        222, 112, 240, 252, 151, 115, 97, 14, 180, 2, 72, 92, 221, 220, 66, 124,
    )
}
