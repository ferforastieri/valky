package com.ferforastieri.valkyris

import com.ferforastieri.valkyris.core.media.awaitUsableIceOffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IceOfferTest {
    private val local = "v=0\r\na=candidate:1 1 udp 2130706431 192.168.1.2 5000 typ host\r\n"

    @Test fun slowStunStillUsesLocalCandidate() = runTest {
        val gathering = CompletableDeferred<Unit>()
        assertEquals(local, awaitUsableIceOffer(gathering, 8000) { local })
        assertFalse(gathering.isCancelled)
        assertFalse(gathering.isCompleted)
    }

    @Test fun completedGatheringUsesLatestDescription() = runTest {
        val gathering = CompletableDeferred(Unit)
        assertEquals(local, awaitUsableIceOffer(gathering) { local })
    }

    @Test fun noCandidatesReportsUsefulError() = runTest {
        try {
            awaitUsableIceOffer(CompletableDeferred(), 8000) { "v=0" }
            fail("An offer without candidates must not be sent")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("endereço de rede"))
            assertFalse(error.message!!.contains("8000"))
        }
    }

    @Test fun closingPlayerCancelsNegotiation() = runTest {
        var readDescription = false
        val job = launch {
            awaitUsableIceOffer(CompletableDeferred()) { readDescription = true; local }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertFalse(readDescription)
    }
}
