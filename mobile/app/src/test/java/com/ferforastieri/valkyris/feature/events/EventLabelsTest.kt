package com.ferforastieri.valkyris.feature.events

import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.ValkyrisEvent
import com.ferforastieri.valkyris.core.model.detectorLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EventLabelsTest {
    @Test fun locationEventsNeverUseGenericDetectorLabel() {
        assertEquals(R.string.event_place_entered, detectorLabelRes("place_entered"))
        assertEquals(R.string.event_place_exited, detectorLabelRes("place_exited"))
        assertEquals(R.string.detector_baby_cry, detectorLabelRes(" BABY_CRY "))
    }
    @Test fun eventCategoriesMatchTheirSource() {
        fun event(type: String, source: String = "camera") = ValkyrisEvent(id = "test", type = type, source = source, confidence = 0.9, occurredAt = "2026-09-07T00:00:00Z")
        assertEquals(R.string.event_category_location, eventCategoryRes(event("place_entered", "tracking")))
        for (type in listOf("baby_cry", "crying", "scream", "doorbell", "dog_bark", "smoke_alarm")) {
            assertEquals(R.string.event_category_audio, eventCategoryRes(event(type)))
            assertNotEquals(R.string.detector_other, detectorLabelRes(type))
        }
        assertEquals(R.string.event_category_camera, eventCategoryRes(event("motion")))
    }
}
