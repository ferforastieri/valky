package com.ferforastieri.valkyris

import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ferforastieri.valkyris.core.alarm.AlarmContent
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w360dp-h640dp", sdk = [35])
class AlarmLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun alarmMatchesAppThemeAndCentersContent() {
        compose.setContent { ValkyrisTheme("light") { AlarmContent("Choro de bebê", "Verifique a câmera para acompanhar o que aconteceu.", "Quarto do bebê") } }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Choro de bebê").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(root.center.x - title.center.x) < 2f)
        compose.onNodeWithText("Silenciar").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/alarm-preview.png")
    }

    @Test fun alarmIsCenteredAndActionsRemainReachableWithLargeText() {
        var silenced = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                ValkyrisTheme {
                    AlarmContent("Choro no quarto do bebê", "Verifique a câmera do quarto. Um som foi detectado.", "Quarto do bebê", onSilence = { silenced = true })
                }
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Choro no quarto do bebê").fetchSemanticsNode().boundsInRoot
        assertTrue("Alarm title must be horizontally centered", kotlin.math.abs(root.center.x - title.center.x) < 2f)
        compose.onNodeWithText("Silenciar").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(silenced)
    }
}
