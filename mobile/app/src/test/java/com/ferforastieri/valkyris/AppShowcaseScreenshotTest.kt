package com.ferforastieri.valkyris

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SlidersHorizontal
import com.ferforastieri.valkyris.core.database.EventEntity
import com.ferforastieri.valkyris.core.database.RuleEntity
import com.ferforastieri.valkyris.core.design.FloatingDock
import com.ferforastieri.valkyris.core.design.ValkyrisTopBar
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.model.Camera as CameraModel
import com.ferforastieri.valkyris.core.model.Capabilities
import com.ferforastieri.valkyris.core.model.RetentionSettings
import com.ferforastieri.valkyris.feature.overview.OverviewContent
import com.ferforastieri.valkyris.feature.cameras.CamerasContent
import com.ferforastieri.valkyris.feature.cameras.CamerasState
import com.ferforastieri.valkyris.feature.events.EventsContent
import com.ferforastieri.valkyris.feature.rules.RulesContent
import com.ferforastieri.valkyris.feature.settings.SettingsContent
import com.ferforastieri.valkyris.feature.settings.LanguageSheet
import com.ferforastieri.valkyris.feature.settings.PermissionsSheet
import com.ferforastieri.valkyris.feature.settings.RetentionSheet
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "pt-rBR-w390dp-h844dp-xxhdpi", sdk = [35])
class AppShowcaseScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overviewLight() = capture("overview-light.png", false, "Visão geral", 0) {
        OverviewContent(Samples.cameras, 3, Samples.events)
    }
    @Test fun overviewDark() = capture("overview-dark.png", true, "Visão geral", 0) {
        OverviewContent(Samples.cameras, 3, Samples.events)
    }
    @Test fun camerasLight() = capture("cameras-light.png", false, "Câmeras", 1) {
        CamerasContent(CamerasState(loading = false, cameras = Samples.cameras))
    }
    @Test fun camerasDark() = capture("cameras-dark.png", true, "Câmeras", 1) {
        CamerasContent(CamerasState(loading = false, cameras = Samples.cameras))
    }
    @Test fun eventsLight() = capture("events-light.png", false, "Eventos", -1, events = true) {
        EventsContent(Samples.events)
    }
    @Test fun eventsDark() = capture("events-dark.png", true, "Eventos", -1, events = true) {
        EventsContent(Samples.events)
    }
    @Test fun rulesLight() = capture("rules-light.png", false, "Regras", 2) {
        RulesContent(Samples.rules)
    }
    @Test fun rulesDark() = capture("rules-dark.png", true, "Regras", 2) {
        RulesContent(Samples.rules)
    }
    @Test fun settingsLight() = capture("settings-light.png", false, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "light",
        )
    }
    @Test fun settingsDark() = capture("settings-dark.png", true, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "dark",
        )
    }
    @Test fun languageSheetLight() = capture("language-sheet-light.png", false, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "light",
        )
        LanguageSheet(current = "pt-BR", onSelect = {}, onDismiss = {})
    }
    @Test fun languageSheetDark() = capture("language-sheet-dark.png", true, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "dark",
        )
        LanguageSheet(current = "pt-BR", onSelect = {}, onDismiss = {})
    }
    @Test fun permissionsSheetLight() = capture("permissions-sheet-light.png", false, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "light",
        )
        PermissionsSheet(
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
        )
    }
    @Test fun permissionsSheetDark() = capture("permissions-sheet-dark.png", true, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "dark",
        )
        PermissionsSheet(
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
        )
    }
    @Test fun retentionSheetLight() = capture("retention-sheet-light.png", false, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "light",
        )
        RetentionSheet(RetentionSettings())
    }
    @Test fun retentionSheetDark() = capture("retention-sheet-dark.png", true, "Ajustes", 3) {
        SettingsContent(
            admin = true,
            notificationsAllowed = true,
            fullScreenAllowed = false,
            dndAllowed = false,
            language = "pt-BR",
            theme = "dark",
        )
        RetentionSheet(RetentionSettings())
    }

    private fun capture(
        name: String,
        dark: Boolean,
        title: String,
        selectedIndex: Int,
        events: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            ValkyrisTheme(if (dark) "dark" else "light") {
                Showcase(title, selectedIndex, events, content)
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(screenshotDirectory().resolve(name).absolutePath)
    }

    private fun screenshotDirectory(): File {
        var directory = File(System.getProperty("user.dir")).canonicalFile
        while (!File(directory, "web").isDirectory && directory.parentFile != null) directory = directory.parentFile
        return File(directory, "web/public/screenshots").apply { mkdirs() }
    }
}

@Composable
private fun Showcase(
    title: String,
    selectedIndex: Int,
    events: Boolean,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
    ) {
      Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            ValkyrisTopBar(title, {}, notificationsSelected = events, unreadNotifications = 1)
            Box(Modifier.weight(1f)) { content() }
        }
        FloatingDock(
            listOf(
                Lucide.House to "Visão geral",
                Lucide.Camera to "Câmeras",
                Lucide.SlidersHorizontal to "Regras",
                Lucide.Settings to "Ajustes",
            ),
            selectedIndex,
            {},
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
        )
      }
    }
}

private object Samples {
    val cameras = listOf(
        CameraModel("entry", "Entrada", "192.0.2.23", icon = "entrance", capabilities = Capabilities(events = true, ptz = true, audio = true)),
        CameraModel("yard", "Quintal", "192.0.2.24", icon = "yard", capabilities = Capabilities(events = true, audio = true)),
    )
    val events = listOf(
        EventEntity("1", "entry", "movimento na entrada", .94, "2026-09-04T20:41:08Z", null, null, null),
        EventEntity("2", "yard", "campainha", .88, "2026-09-04T19:22:00Z", null, null, "2026-09-04T19:23:00Z"),
        EventEntity("3", "yard", "latido", .82, "2026-09-04T18:06:00Z", null, null, "2026-09-04T18:07:00Z"),
    )
    val rules = listOf(
        RuleEntity("1", "entry", "Movimento na entrada", "motion", true),
        RuleEntity("2", "yard", "Avisar quando o cachorro latir", "dog_bark", true),
        RuleEntity("3", "entry", "Alarme de fumaça", "smoke_alarm", true),
    )
}
