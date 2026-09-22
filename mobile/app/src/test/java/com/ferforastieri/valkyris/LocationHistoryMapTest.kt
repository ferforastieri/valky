package com.ferforastieri.valkyris

import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.design.mapColorWithAlpha
import com.ferforastieri.valkyris.feature.people.historyRouteSegments
import com.ferforastieri.valkyris.feature.people.validHistoryPoint
import org.junit.Assert.*
import org.junit.Test

class LocationHistoryMapTest {
    private fun point(id: String, at: String, until: String = "") = PersonLocation(id=id,personId="user",latitude=-23.55,longitude=-46.63,accuracy=10.0,occurredAt="2026-09-19T${at}:00Z",lastSeenAt=until.takeIf { it.isNotBlank() }?.let { "2026-09-19T${it}:00Z" }.orEmpty())
    @Test fun routeUsesChronologicalOrderAndLeavesGapsInMissingObservations() {
        val a = point("a", "10:00", "12:00")
        val b = point("b", "12:10")
        val c = point("c", "16:00")
        val d = point("d", "16:10")
        val segments = historyRouteSegments(listOf(d,c,b,a))
        assertEquals(listOf(listOf(a,b),listOf(c,d)), segments)
    }
    @Test fun invalidCoordinatesNeverBecomeMapPoints() {
        assertFalse(validHistoryPoint(point("bad", "10:00").copy(latitude=91.0)))
        assertFalse(validHistoryPoint(point("bad", "10:00").copy(longitude=Double.NaN)))
        assertTrue(historyRouteSegments(listOf(point("single", "10:00"))).isEmpty())
    }
    @Test fun mapOverlaysPreserveThemeColorWhenApplyingTransparency() {
        assertEquals(0x247BD66F, mapColorWithAlpha(0xFF7BD66F.toInt(), 0x24))
        assertEquals(0xFF7BD66F.toInt(), mapColorWithAlpha(0xFF7BD66F.toInt(), 999))
    }
}
