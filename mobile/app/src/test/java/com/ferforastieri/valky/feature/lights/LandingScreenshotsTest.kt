package com.ferforastieri.valky.feature.lights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MonitorSmartphone
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.UserRound
import com.ferforastieri.valky.core.design.ValkyTheme
import com.ferforastieri.valky.core.design.ValkyTopBar
import com.ferforastieri.valky.core.model.Camera
import com.ferforastieri.valky.core.model.LightCapabilities
import com.ferforastieri.valky.core.model.LightColor
import com.ferforastieri.valky.core.model.LightDevice
import com.ferforastieri.valky.core.model.LightState
import com.ferforastieri.valky.core.model.ValkyEvent
import com.ferforastieri.valky.feature.cameras.CamerasContent
import com.ferforastieri.valky.feature.cameras.CamerasState
import com.ferforastieri.valky.feature.events.EventsContent
import com.ferforastieri.valky.feature.overview.FavoriteItem
import com.ferforastieri.valky.feature.overview.OverviewContent
import com.ferforastieri.valky.feature.rules.RuleCard
import com.ferforastieri.valky.feature.settings.SettingsContent
import com.ferforastieri.valky.core.design.FloatingDock
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas das telas principais na íntegra (barra de cima, conteúdo e dock de
 * navegação), em português, claro e escuro, no tamanho de celular.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LandingScreenshotsTest {
    init { java.util.Locale.setDefault(java.util.Locale("pt", "BR")) }

    @get:Rule val compose = createComposeRule()

    private val dock = listOf(
        Lucide.House to "Visão geral",
        Lucide.MapPin to "Localização",
        Lucide.MonitorSmartphone to "Dispositivos",
        Lucide.UserRound to "Perfil",
        Lucide.Settings to "Ajustes",
    )

    private fun lamp(name: String, on: Boolean, hue: Int, brightness: Int = 88) = LightDevice(
        id = "luz-$name", name = name,
        capabilities = LightCapabilities(brightness = true, color = true, colorTemperature = true),
        setupStatus = "ready", favorite = true, enabled = true,
        state = LightState(online = true, power = on, mode = "color", brightness = brightness,
            temperatureKelvin = 4000, color = LightColor(hue = hue, saturation = 100, value = brightness)),
        deviceId = "eb83892f581112f805ytyu", protocolVersion = 3.5, lastAddress = "192.0.2.111",
    )

    private fun tela(modo: String, titulo: String, selecionado: Int, proprioCabecalho: Boolean = false, conteudo: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            ValkyTheme(mode = modo) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (!proprioCabecalho) ValkyTopBar(title = titulo, onNotifications = {}, unreadNotifications = 3)
                    Box(Modifier.weight(1f)) { conteudo() }
                    Box(Modifier.fillMaxWidth().padding(bottom = 18.dp), contentAlignment = Alignment.Center) {
                        FloatingDock(dock, selecionado, {})
                    }
                }
            }
        }
    }

    private fun capturar(nome: String) = compose.onRoot().captureRoboImage("build/screenshots/$nome.png")

    private fun cameras() = CamerasState(cameras = listOf(
        Camera(id = "cam-1", name = "Entrada principal", icon = "camera", host = "192.0.2.50", setupStatus = "ready", favorite = true),
        Camera(id = "cam-2", name = "Quintal", icon = "camera", host = "192.0.2.51", setupStatus = "ready"),
    ), loading = false)

    private fun eventos() = listOf(
        ValkyEvent(id = "e1", cameraId = "cam-1", type = "pessoa", confidence = 0.93, occurredAt = "2026-10-17T14:02:00Z"),
        ValkyEvent(id = "e2", cameraId = "cam-2", type = "movimento", confidence = 0.81, occurredAt = "2026-10-17T13:40:00Z"),
        ValkyEvent(id = "e3", cameraId = "cam-1", type = "som", confidence = 0.77, occurredAt = "2026-10-17T12:15:00Z"),
    )

    private fun regra(nome: String) = com.ferforastieri.valky.core.model.Rule(cameraId = "cam-1", name = nome, detectorTypes = listOf("pessoa"))

    @androidx.compose.runtime.Composable
    private fun regras() = Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RuleCard(regra("Pessoa na entrada"), {}, {})
        RuleCard(regra("Movimento no quintal"), {}, {})
        RuleCard(regra("Choro do bebê"), {}, {})
    }

    private fun listaRegras() = listOf(com.ferforastieri.valky.core.model.Rule(cameraId = "cam-1", name = "Pessoa na entrada", detectorTypes = listOf("pessoa")), com.ferforastieri.valky.core.model.Rule(cameraId = "cam-1", name = "Movimento no quintal", detectorTypes = listOf("pessoa")))

    @androidx.compose.runtime.Composable
    private fun lista() = Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LightCard(lamp("Luz Suíte", true, 30), false, {}, {}, null, {}, {})
        LightCard(lamp("Sala de TV", true, 205, 40), false, {}, {}, null, {}, {})
        LightCard(lamp("Sala de Jantar", false, 0, 100), false, {}, {}, null, {}, {})
    }

    private fun favoritos() = listOf(
        FavoriteItem("light", "luz-1", "Luz Suíte", online = true, power = true),
        FavoriteItem("camera", "cam-1", "Entrada principal", online = true),
        FavoriteItem("light", "luz-2", "Sala de TV", online = true, power = true, hue = 205),
        FavoriteItem("remote", "rem-1", "TV da sala"),
    )

    @Test fun overviewClara() { tela("light", "Visão geral", 0) { OverviewContent(cameras = emptyList(), trackedPeople = 2, events = eventos(), favorites = favoritos()) }; capturar("landing-overview-light") }
    @Test fun overviewEscura() { tela("dark", "Visão geral", 0) { OverviewContent(cameras = emptyList(), trackedPeople = 2, events = eventos(), favorites = favoritos()) }; capturar("landing-overview-dark") }
    @Test fun camerasClara() { tela("light", "Câmeras", 2) { CamerasContent(cameras()) }; capturar("landing-cameras-light") }
    @Test fun camerasEscura() { tela("dark", "Câmeras", 2) { CamerasContent(cameras()) }; capturar("landing-cameras-dark") }
    @Test fun eventosClara() { tela("light", "Notificações", 0) { EventsContent(eventos()) }; capturar("landing-events-light") }
    @Test fun eventosEscura() { tela("dark", "Notificações", 0) { EventsContent(eventos()) }; capturar("landing-events-dark") }
    // Capturas das regras: voltam à landing com a identidade nova.
    @Test fun regrasClara() { tela("light", "Regras", 4) { regras() }; capturar("landing-rules-light") }
    @Test fun regrasEscura() { tela("dark", "Regras", 4) { regras() }; capturar("landing-rules-dark") }
    @Test fun ajustesClara() { tela("light", "Ajustes", 4) { SettingsContent(admin = true, permissionsReady = 0, permissionsTotal = 0, language = "", theme = "") }; capturar("landing-settings-light") }
    @Test fun ajustesEscura() { tela("dark", "Ajustes", 4) { SettingsContent(admin = true, permissionsReady = 0, permissionsTotal = 0, language = "", theme = "") }; capturar("landing-settings-dark") }
    @Test fun listaClara() { tela("light", "Iluminação", 2) { lista() }; capturar("landing-lista-light") }
    @Test fun listaEscura() { tela("dark", "Iluminação", 2) { lista() }; capturar("landing-lista-dark") }
    @Test fun luzClara() { tela("light", "", 2, proprioCabecalho = true) { LightDetailContent(lamp("Luz Suíte", true, 30), false, {}, onChange = {}) }; capturar("landing-luz-light") }
    @Test fun luzEscura() { tela("dark", "", 2, proprioCabecalho = true) { LightDetailContent(lamp("Luz Suíte", true, 30), false, {}, onChange = {}) }; capturar("landing-luz-dark") }
}
