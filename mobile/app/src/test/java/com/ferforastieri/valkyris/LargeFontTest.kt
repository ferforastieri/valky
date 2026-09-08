package com.ferforastieri.valkyris

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ferforastieri.valkyris.core.design.ValkyrisTheme
import com.ferforastieri.valkyris.core.model.ManagedUser
import com.ferforastieri.valkyris.feature.settings.UserManagementSheet
import com.ferforastieri.valkyris.feature.overview.OverviewContent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "pt-rBR-w390dp-h844dp", sdk = [35])
class LargeFontTest {
    @get:Rule val compose = createComposeRule()

    @Test fun userEditorRemainsScrollableAndSaveIsReachableAtDoubleFontSize() {
        var saved: ManagedUser? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                ValkyrisTheme {
                    UserManagementSheet(listOf(ManagedUser(id = "miriam", name = "Miriam da Silva", devices = 2)), null, false, { saved = it }, {}, {})
                }
            }
        }
        compose.onNodeWithText("Editar").performScrollTo().performClick()
        compose.onNodeWithText("Visualizar regras").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Salvar alterações").performScrollTo().assertIsDisplayed().performClick()
        assertNotNull(saved)
    }

    @Test fun overviewCardsMoveToSeparateRowsWithLargeFonts() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                ValkyrisTheme { OverviewContent(emptyList(), 2, emptyList()) }
            }
        }
        val cameras = compose.onNodeWithText("Câmeras", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val family = compose.onNodeWithText("Pessoas", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Cards must flow vertically instead of squeezing enlarged labels", family.top > cameras.bottom)
    }
}
