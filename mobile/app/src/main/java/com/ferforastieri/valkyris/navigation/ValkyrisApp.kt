package com.ferforastieri.valkyris.navigation

import androidx.compose.foundation.layout.*
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
import androidx.navigation.NavHostController
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
import com.ferforastieri.valkyris.core.design.FloatingDock
import com.ferforastieri.valkyris.core.design.ValkyrisTopBar
import com.ferforastieri.valkyris.feature.overview.OverviewScreen
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Video

private data class Destination(val route: String, val label: Int, val icon: ImageVector)

private val destinations = listOf(
    Destination("overview", R.string.overview, Lucide.House),
    Destination("cameras", R.string.cameras, Lucide.Video),
    Destination("rules", R.string.rules, Lucide.SlidersHorizontal),
    Destination("settings", R.string.settings, Lucide.Settings),
)

@Composable
fun ValkyrisApp(main: MainViewModel) {
    val paired by main.paired.collectAsStateWithLifecycle()
    val admin by main.admin.collectAsStateWithLifecycle()
    val update by main.updateInfo.collectAsStateWithLifecycle()
    val updating by main.updating.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!paired) {
                OnboardingScreen(main)
            } else {
                ConnectedValkyrisApp(main)
            }
            ToastMessageHost(
                notices = main.notices,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(14.dp),
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
}

@Composable
private fun ConnectedValkyrisApp(main: MainViewModel) {
    val nav = rememberNavController()
    val eventsViewModel: com.ferforastieri.valkyris.feature.events.EventsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val events by eventsViewModel.events.collectAsStateWithLifecycle()
    val unreadNotifications = events.count { it.acknowledgedAt == null }
    StartupAlertPermissions()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val selectedIndex = destinations.indexOfFirst { it.route == currentRoute }
    val showBottomBar = selectedIndex >= 0 || currentRoute == "events" || currentRoute == "camera/{id}"
    val showTopBar = selectedIndex >= 0 || currentRoute == "events"
    val pendingEvent by main.pendingEvent.collectAsStateWithLifecycle()
    val pendingCamera by main.pendingCamera.collectAsStateWithLifecycle()
    LaunchedEffect(pendingEvent) {
        pendingEvent?.let {
            nav.navigate("event/$it") { launchSingleTop = true }
            main.eventOpened()
        }
    }
    LaunchedEffect(pendingCamera) {
        pendingCamera?.let {
            nav.navigate("camera/$it") { launchSingleTop = true }
            main.cameraOpened()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                val title = when (currentRoute) {
                    "cameras" -> stringResource(R.string.cameras)
                    "rules" -> stringResource(R.string.rules)
                    "settings" -> stringResource(R.string.settings)
                    "events" -> stringResource(R.string.events)
                    else -> stringResource(R.string.overview)
                }
                ValkyrisTopBar(
                    title = title,
                    notificationsSelected = currentRoute == "events",
                    unreadNotifications = unreadNotifications,
                    onNotifications = {
                        if (currentRoute != "events") nav.navigate("events") { launchSingleTop = true }
                    },
                )
            }
        },
        bottomBar = {
            if (showBottomBar) Box(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                val labels = destinations.map { it.icon to stringResource(it.label) }
                FloatingDock(labels, selectedIndex, onSelect = { index ->
                    nav.openTopLevel(destinations[index].route)
                })
            }
        },
    ) { padding ->
        NavHost(nav, "overview", Modifier.padding(padding)) {
            composable("overview") { OverviewScreen(onCamera = { nav.navigate("camera/$it") }, onEvent = { nav.navigate("event/$it") }) }
            composable("cameras") { CamerasScreen(onCamera = { nav.navigate("camera/$it") }) }
            composable("camera/{id}") { CameraLiveScreen(cameraId = it.arguments?.getString("id").orEmpty()) }
            composable("events") {
                EventsScreen(
                    onEvent = { nav.navigate("event/$it") },
                    onCamera = { nav.navigate("camera/$it") },
                    vm = eventsViewModel,
                )
            }
            composable("event/{id}") { EventDetailScreen(onBack = { nav.popBackStack() }) }
            composable("rules") { RulesScreen() }
            composable("settings") { SettingsScreen(main) }
        }
    }
}

private fun NavHostController.openTopLevel(route: String) {
    if (route == "overview") {
        if (!popBackStack("overview", inclusive = false) && currentDestination?.route != "overview") {
            navigate("overview") { launchSingleTop = true }
        }
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
