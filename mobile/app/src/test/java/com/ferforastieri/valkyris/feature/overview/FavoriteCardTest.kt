package com.ferforastieri.valkyris.feature.overview

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O favorito da lâmpada na visão geral: tocar no cartão abre a tela da luz e
 * tocar na lâmpada acende ali mesmo, igual à lista de lâmpadas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FavoriteCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun lamp() = FavoriteItem(
        type = "light",
        id = "luz-1",
        name = "Luz Suíte",
        online = true,
        power = true,
    )

    @Test
    fun tocarNoCartaoAbreATelaDaLuz() {
        var opened: String? = null
        compose.setContent {
            ValkyrisTheme(mode = "dark") {
                FavoriteCard(lamp(), Modifier, onClick = { opened = "luz-1" }) {}
            }
        }
        compose.onNodeWithText("Luz Suíte").performClick()
        assertEquals("luz-1", opened)
    }

    @Test
    fun tocarNaLampadaAcendeAliMesmo() {
        var toggles = 0
        compose.setContent {
            ValkyrisTheme(mode = "dark") {
                FavoriteCard(lamp(), Modifier, onClick = {}) { toggles++ }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.light_toggle)).performClick()
        assertEquals(1, toggles)
    }
}
