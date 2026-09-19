package com.ferforastieri.valkyris

import android.location.Location
import com.ferforastieri.valkyris.feature.people.shouldSendLocation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationReportPolicyTest {
    private val now = 1_800_000_000_000L
    private fun fix() = Location("test").apply { latitude = -23.55; longitude = -46.63; accuracy = 12f; time = now }
    @Test fun reopeningAppSendsFreshLocationWithoutWaitingForMovementOrHeartbeat() {
        assertFalse(shouldSendLocation(fix(), now, now - 10_000, now - 10_000, 0, false, false))
        assertTrue(shouldSendLocation(fix(), now, now - 10_000, now - 10_000, 0, false, true))
    }
    @Test fun forcedRefreshStillRejectsStaleRepeatedAndInaccurateFixes() {
        assertFalse(shouldSendLocation(fix().apply { time = now - 121_000 }, now, 0, 0, 0, true, true))
        assertFalse(shouldSendLocation(fix().apply { accuracy = 201f }, now, 0, 0, 0, true, true))
        assertFalse(shouldSendLocation(fix(), now, now, now, 0, true, true))
        assertFalse(shouldSendLocation(fix().apply { latitude = Double.NaN }, now, 0, 0, 0, true, true))
    }
    @Test fun backgroundRetainsMovementHeartbeatAndGeofenceConfirmationCadence() {
        assertTrue(shouldSendLocation(fix(), now, now - 60_000, now - 60_000, 0, true, false))
        assertTrue(shouldSendLocation(fix(), now, now - 60_000, now - 60_000, now + 60_000, false, false))
        assertTrue(shouldSendLocation(fix(), now, now - 900_000, now - 900_000, 0, false, false))
        assertFalse(shouldSendLocation(fix(), now, now - 10_000, now - 10_000, now + 60_000, true, false))
    }
}
