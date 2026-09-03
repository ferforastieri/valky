package com.ferforastieri.valkyris.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ferforastieri.valkyris.MainViewModel
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.feature.cameras.CameraLiveScreen
import com.ferforastieri.valkyris.feature.cameras.CamerasScreen
import com.ferforastieri.valkyris.feature.events.EventDetailScreen
import com.ferforastieri.valkyris.feature.events.EventsScreen
import com.ferforastieri.valkyris.feature.onboarding.OnboardingScreen
import com.ferforastieri.valkyris.feature.rules.RulesScreen
import com.ferforastieri.valkyris.feature.settings.SettingsScreen
import com.ferforastieri.valkyris.feature.settings.StartupAlertPermissions
import com.ferforastieri.valkyris.core.design.ToastMessageHost
import com.ferforastieri.valkyris.core.design.UpdateEventDialog
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Video

private data class Destination(val route: String, val label: Int)

private val destinations = listOf(
    Destination("cameras", R.string.cameras),
    Destination("events", R.string.events),
    Destination("rules", R.string.rules),
    Destination("settings", R.string.settings),
)

@Composable
private fun Destination.icon(): ImageVector = when (route) {
    "cameras" -> Lucide.Video
    "events" -> Lucide.Bell
    "rules" -> Lucide.SlidersHorizontal
    else -> Lucide.Settings
}

@Composable
fun ValkyrisApp(main: MainViewModel) {
    val paired by main.paired.collectAsStateWithLifecycle()
    val admin by main.admin.collectAsStateWithLifecycle()
    val update by main.updateInfo.collectAsStateWithLifecycle()
    val updating by main.updating.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        if (!paired) {
            OnboardingScreen(main)
        } else {
            ConnectedValkyrisApp(main)
        }
        ToastMessageHost(
            notices = main.notices,
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(14.dp),
        )
        update?.let {
            UpdateEventDialog(
                update = it,
                admin = admin,
                updating = updating,
                onUpdate = main::startUpdate,
                onDismiss = main::dismissUpdate,
            )
        }
    }
}

@Composable
private fun ConnectedValkyrisApp(main: MainViewModel) {
    val nav = rememberNavController()
    StartupAlertPermissions()
    val entry by nav.currentBackStackEntryAsState()
    val showBottomBar = destinations.any { it.route == entry?.destination?.route }
    val pendingEvent by main.pendingEvent.collectAsStateWithLifecycle()
    LaunchedEffect(pendingEvent) {
        pendingEvent?.let {
            nav.navigate("event/$it") { launchSingleTop = true }
            main.eventOpened()
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 10.dp,
                    tonalElevation = 1.dp,
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = entry?.destination?.route == destination.route,
                                onClick = {
                                    nav.navigate(destination.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        destination.icon(),
                                        contentDescription = stringResource(destination.label),
                                        modifier = Modifier.size(21.dp),
                                    )
                                },
                                alwaysShowLabel = false,
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.secondary,
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, "cameras", Modifier.padding(padding)) {
            composable("cameras") { CamerasScreen(onCamera = { nav.navigate("camera/$it") }) }
            composable("camera/{id}") { CameraLiveScreen(cameraId = it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
            composable("events") { EventsScreen(onEvent = { nav.navigate("event/$it") }) }
            composable("event/{id}") { EventDetailScreen(onBack = { nav.popBackStack() }) }
            composable("rules") { RulesScreen() }
            composable("settings") { SettingsScreen(main) }
        }
    }
}
