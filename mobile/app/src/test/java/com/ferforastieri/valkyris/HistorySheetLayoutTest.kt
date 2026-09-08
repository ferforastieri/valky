package com.ferforastieri.valkyris

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.feature.people.HistorySheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "pt-rBR-w390dp-h844dp-xxhdpi", sdk = [35])
class HistorySheetLayoutTest {
 @get:Rule val compose = createComposeRule()
 @Test fun historyShowsTimelineAndRequestsAnOlderPage() {
  var requested = false
  compose.setContent {
   ValkyrisTheme {
    HistorySheet(TrackedPerson(name="Miriam"), (0..5).map {
     PersonLocation(id="location-$it",address="Rua $it",latitude=-23.55+it*0.001,longitude=-46.63,accuracy=12.0,occurredAt="2026-09-07T14:0${it}:00Z")
    }, more = true, onMore = { requested = true }) {}
   }
  }
  compose.onNodeWithText("Rua 0").assertIsDisplayed()
  compose.onAllNodesWithText("Ponto", substring=true).assertCountEquals(0)
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ver registros anteriores"))
  compose.onNodeWithText("Ver registros anteriores").performClick()
  org.junit.Assert.assertTrue(requested)
 }
}
