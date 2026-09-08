package com.ferforastieri.valkyris

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.ferforastieri.valkyris.navigation.openTopLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TopLevelNavigationTest {
    @Test fun locationNeverRestoresNotificationsOnRepeatedTabChanges() {
        val nav = NavHostController(RuntimeEnvironment.getApplication())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = "overview") {
            composable("overview") {}
            composable("people") {}
            composable("events") {}
        }
        repeat(4) {
            nav.openTopLevel("people")
            assertEquals("people", nav.currentDestination?.route)
            nav.navigate("events")
            assertEquals("events", nav.currentDestination?.route)
        }
        nav.openTopLevel("people")
        assertEquals("people", nav.currentDestination?.route)
        nav.openTopLevel("overview")
        assertEquals("overview", nav.currentDestination?.route)
    }
}
