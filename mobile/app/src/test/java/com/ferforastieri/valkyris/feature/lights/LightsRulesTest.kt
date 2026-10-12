package com.ferforastieri.valkyris.feature.lights

import com.ferforastieri.valkyris.core.model.LightCandidate
import com.ferforastieri.valkyris.core.model.LightDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightsRulesTest {
    private fun light(id: String, deviceId: String, address: String) =
        LightDevice(id = id, name = id, deviceId = deviceId, lastAddress = address)

    @Test fun localKeyNeedsExactlySixteenCharacters() {
        assertTrue(lightKeyValid("0123456789abcdef"))
        assertTrue(lightKeyValid("  0123456789abcdef  "))
        assertFalse(lightKeyValid("0123456789abcde"))
        assertFalse(lightKeyValid("0123456789abcdefg"))
        assertFalse(lightKeyValid(""))
    }

    @Test fun discoveryNeverOffersARegisteredBulbAgain() {
        val registered = listOf(light("a", deviceId = "device-1", address = "192.168.1.50"))
        val candidates = listOf(
            LightCandidate(address = "192.168.1.50", deviceId = "device-1"),
            LightCandidate(address = "192.168.1.51", deviceId = "device-2"),
            LightCandidate(address = "192.168.1.52"),
        )
        val available = unregisteredCandidates(candidates, registered)
        assertEquals(listOf("192.168.1.51", "192.168.1.52"), available.map { it.address })
    }

    @Test fun aBulbThatHidesItsIdentifierIsStillMatchedByAddress() {
        val registered = listOf(light("a", deviceId = "", address = "192.168.1.50"))
        val candidates = listOf(LightCandidate(address = "192.168.1.50", deviceId = "device-1"))
        assertTrue(unregisteredCandidates(candidates, registered).isEmpty())
    }
}
