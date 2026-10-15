package com.ferforastieri.valkyris.feature.lights

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.ScreenshotTest
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.model.LightCapabilities
import com.ferforastieri.valkyris.core.model.LightColor
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.LightState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the light screens to PNGs (no emulator) so the layout can be compared
 * with the reference design. Files land in app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.junit.experimental.categories.Category(ScreenshotTest::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LightScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    private fun sample(
        power: Boolean = true,
        mode: String = "white",
        brightness: Int = 99,
        kelvin: Int = 4000,
        hue: Int = 30,
    ) = LightDevice(
        id = "luz-1",
        name = "Luz Suíte",
        capabilities = LightCapabilities(brightness = true, color = true, colorTemperature = true),
        setupStatus = "ready",
        favorite = true,
        enabled = true,
        state = LightState(
            online = true,
            power = power,
            mode = mode,
            brightness = brightness,
            temperatureKelvin = kelvin,
            color = LightColor(hue = hue, saturation = 100, value = brightness),
        ),
        deviceId = "eb83892f581112f805ytyu",
        protocolVersion = 3.5,
        lastAddress = "192.0.2.102",
    )

    @Test
    fun lightDetailWhite() {
        compose.setContent {
            ValkyrisTheme(mode = "dark") {
                LightDetailContent(sample(mode = "white", kelvin = 4000), false, {}, onChange = {})
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/light-detail-branco.png")
    }

    @Test
    fun lightDetailColor() {
        compose.setContent {
            ValkyrisTheme(mode = "dark") {
                LightDetailContent(sample(mode = "color", hue = 30), false, {}, onChange = {})
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/light-detail-cor.png")
    }

    @Test
    fun lightCards() {
        compose.setContent {
            ValkyrisTheme(mode = "dark") {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(16.dp)) {
                    LightCard(sample(power = true), false, {}, {}, null, {}, {})
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                    LightCard(sample(power = false), false, {}, {}, null, {}, {})
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/light-cards.png")
    }
}
