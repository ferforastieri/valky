package com.ferforastieri.valkyris.feature.lights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.design.ValkyrisTopBar
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
 * Capturas no padrão da landing: tela inteira do app, fundo do próprio app,
 * par claro/escuro, tamanho de celular. Arquivos em app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LandingScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    private fun lamp(name: String, on: Boolean, hue: Int, brightness: Int = 88) = LightDevice(
        id = "luz-$name",
        name = name,
        capabilities = LightCapabilities(brightness = true, color = true, colorTemperature = true),
        setupStatus = "ready",
        favorite = true,
        enabled = true,
        state = LightState(
            online = true,
            power = on,
            mode = "color",
            brightness = brightness,
            temperatureKelvin = 4000,
            color = LightColor(hue = hue, saturation = 100, value = brightness),
        ),
        deviceId = "eb83892f581112f805ytyu",
        protocolVersion = 3.5,
        lastAddress = "192.0.2.111",
    )

    private fun tela(modo: String, conteudo: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            ValkyrisTheme(mode = modo) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ValkyrisTopBar(title = "Iluminação", onNotifications = {}, unreadNotifications = 3)
                    conteudo()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun lista() {
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LightCard(lamp("Luz Suíte", true, 30), false, {}, {}, null, {}, {})
            LightCard(lamp("Sala de TV", true, 205, 40), false, {}, {}, null, {}, {})
            LightCard(lamp("Sala de Jantar", false, 0, 100), false, {}, {}, null, {}, {})
        }
    }

    @Test fun listaClara() { tela("light") { lista() }; compose.onRoot().captureRoboImage("build/screenshots/landing-lista-light.png") }
    @Test fun listaEscura() { tela("dark") { lista() }; compose.onRoot().captureRoboImage("build/screenshots/landing-lista-dark.png") }
    @Test fun luzClara() { tela("light") { LightDetailContent(lamp("Luz Suíte", true, 30), false, {}, onChange = {}) }; compose.onRoot().captureRoboImage("build/screenshots/landing-luz-light.png") }
    @Test fun luzEscura() { tela("dark") { LightDetailContent(lamp("Luz Suíte", true, 30), false, {}, onChange = {}) }; compose.onRoot().captureRoboImage("build/screenshots/landing-luz-dark.png") }
}
