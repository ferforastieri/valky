package com.ferforastieri.valkyris

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.model.ActivityBucket
import com.ferforastieri.valkyris.core.model.ValkyrisEvent
import com.ferforastieri.valkyris.feature.overview.ActivityChart
import com.ferforastieri.valkyris.feature.overview.ActivitySheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "pt-rBR-w390dp-h844dp", sdk = [35])
class ActivityChartTest {
    @get:Rule val compose = createComposeRule()
    private val bucket = ActivityBucket("2026-09-07T10:00:00Z", "2026-09-07T11:00:00Z", 0)

    @Test fun emptyBarsRemainSelectableAndAllPeriodsAreAvailable() {
        var selected: ActivityBucket? = null
        var hours = 12
        compose.setContent { ValkyrisTheme { ActivityChart(12, listOf(bucket), onHours = { hours = it }, onBucket = { selected = it }) } }
        for (period in listOf(12,24,36,48)) {
            compose.onNodeWithText("${period}h").performClick()
            assertEquals(period, hours)
        }
        compose.onNodeWithContentDescription("0 eventos", substring = true).performClick()
        assertEquals(bucket, selected)
    }

    @Test fun intervalSheetLoadsEventsAndOpensTheSelectedEvent() {
        var selected = ""
        val offsets = mutableListOf<Int>()
        compose.setContent { ValkyrisTheme {
            ActivitySheet(bucket.copy(count = 1), load = { offset -> offsets.add(offset); listOf(ValkyrisEvent(id="event-1", type="motion", confidence=.9, occurredAt=bucket.start)) }, onDismiss = {}, onEvent = { selected = it })
        } }
        compose.waitForIdle()
        assertEquals(listOf(0), offsets)
        compose.onNodeWithText("1 eventos neste intervalo").assertIsDisplayed()
        compose.onNodeWithText("Movimento", substring = true, ignoreCase = true).performClick()
        assertEquals("event-1", selected)
    }
}
