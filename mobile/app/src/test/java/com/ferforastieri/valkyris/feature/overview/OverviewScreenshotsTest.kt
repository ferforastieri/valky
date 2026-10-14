package com.ferforastieri.valkyris.feature.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class OverviewScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun favoritesGrid() {
        compose.setContent {
            ValkyrisTheme(mode = "dark") {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(18.dp)) {
                        FavoriteGrid(
                            items = listOf(
                                FavoriteItem("camera", "1", "Câmera Portão", "192.0.2.100"),
                                FavoriteItem("light", "2", "Luz Suíte", "", online = true, power = true),
                                FavoriteItem("light", "3", "Sala de TV", "", online = true, power = false),
                                FavoriteItem("light", "4", "Quarto bebê", "", online = true, power = true),
                                FavoriteItem("remote", "5", "TV Sala", "192.0.2.105"),
                            ),
                            onOpen = {},
                            onToggleLight = {},
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/favorites.png")
    }
}
