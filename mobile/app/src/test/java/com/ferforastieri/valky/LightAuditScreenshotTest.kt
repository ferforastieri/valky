package com.ferforastieri.valky

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.ferforastieri.valky.core.design.ValkyTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Auditoria temporária: renderiza telas e sheets que NÃO entram nas capturas da
 * landing, no tema claro, para conferir contraste de ícones e fundos.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LightAuditScreenshotTest {
    init { java.util.Locale.setDefault(java.util.Locale("pt", "BR")) }

    @get:Rule val compose = createComposeRule()

    private fun tela(nome: String, conteudo: @Composable () -> Unit) {
        compose.setContent {
            ValkyTheme(mode = "light") {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) { conteudo() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/audit-$nome.png")
    }

    @Test
    fun onboarding() {
        tela("onboarding") { Text("auditoria: onboarding", Modifier.fillMaxSize()) }
    }
}
