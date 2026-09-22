package com.ferforastieri.valkyris.core.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushFreshnessTest {
    private val now = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()

    @Test
    fun `accepts a current alert`() {
        assertTrue(isFreshPush("2026-09-22T11:59:30Z", now - 10_000, now))
    }

    @Test
    fun `rejects an alert retained while the device was offline`() {
        assertFalse(isFreshPush("2026-09-22T11:55:00Z", now - 10_000, now))
    }

    @Test
    fun `rejects a current event carried by an expired FCM message`() {
        assertFalse(isFreshPush("2026-09-22T11:59:50Z", now - 61_000, now))
    }

    @Test
    fun `rejects a payload without a valid event timestamp`() {
        assertFalse(isFreshPush("", now, now))
    }
}
