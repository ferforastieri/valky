package com.ferforastieri.valky.feature.lights

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ferforastieri.valky.core.design.ValkyTheme
import com.ferforastieri.valky.core.model.LightCapabilities
import com.ferforastieri.valky.core.model.LightColor
import com.ferforastieri.valky.core.model.LightDevice
import com.ferforastieri.valky.core.model.LightState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O cartão da lista tem dois alvos: o cartão abre a tela da lâmpada e a
 * lâmpada liga/desliga. Este teste garante que os dois continuam funcionando.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LightCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sample() = LightDevice(
        id = "luz-1",
        name = "Luz Suíte",
        capabilities = LightCapabilities(brightness = true, color = true, colorTemperature = true),
        setupStatus = "ready",
        favorite = true,
        enabled = true,
        state = LightState(
            online = true,
            power = true,
            mode = "white",
            brightness = 99,
            temperatureKelvin = 4000,
            color = LightColor(hue = 30, saturation = 100, value = 99),
        ),
        deviceId = "eb83892f581112f805ytyu",
        protocolVersion = 3.5,
        lastAddress = "192.0.2.102",
    )

    @Test
    fun tocarNoCartaoAbreATelaDaLampada() {
        var opened: String? = null
        compose.setContent {
            ValkyTheme(mode = "dark") {
                LightCard(sample(), busy = false, onPower = {}, onOpen = { opened = "luz-1" }, onRemove = null)
            }
        }
        compose.onNodeWithText("Luz Suíte").performClick()
        assertEquals("luz-1", opened)
    }

    @Test
    fun tocarNaLampadaLigaEDesliga() {
        var toggled = 0
        compose.setContent {
            ValkyTheme(mode = "dark") {
                LightCard(sample(), busy = false, onPower = { toggled++ }, onOpen = {}, onRemove = null)
            }
        }
        // O idioma do teste é o do sistema (inglês por padrão): usa o próprio recurso.
        compose.onNodeWithContentDescription(context.getString(com.ferforastieri.valky.R.string.light_toggle)).performClick()
        assertEquals(1, toggled)
    }
}
