@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ferforastieri.valky.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ferforastieri.valky.MainViewModel
import com.ferforastieri.valky.R
import com.ferforastieri.valky.feature.cameras.CameraLiveScreen
import com.ferforastieri.valky.feature.cameras.CamerasScreen
import com.ferforastieri.valky.feature.devices.DevicesScreen
import com.ferforastieri.valky.feature.devices.RemoteControlScreen
import com.ferforastieri.valky.feature.lights.LightDetailScreen
import com.ferforastieri.valky.feature.lights.LightsScreen
import com.ferforastieri.valky.feature.events.EventDetailScreen
import com.ferforastieri.valky.feature.events.EventsScreen
import com.ferforastieri.valky.feature.onboarding.OnboardingScreen
import com.ferforastieri.valky.feature.people.PeopleScreen
import com.ferforastieri.valky.feature.people.PersonDetailScreen
import com.ferforastieri.valky.feature.profile.ProfileScreen
import com.ferforastieri.valky.feature.settings.SettingsScreen
import com.ferforastieri.valky.feature.settings.StartupAlertPermissions
import com.ferforastieri.valky.feature.settings.UpdateAvailableSheet
import com.ferforastieri.valky.core.design.FloatingDock
import com.ferforastieri.valky.core.design.ToastMessageHost
import com.ferforastieri.valky.core.design.ValkyTopBar
import com.ferforastieri.valky.feature.overview.OverviewScreen
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.UserRound
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.MonitorSmartphone

private data class Destination(val route: String, val label: Int, val icon: ImageVector)

private val destinations = listOf(
    Destination("overview", R.string.overview, Lucide.House),
    Destination("people", R.string.location_screen, Lucide.MapPin),
    Destination("devices", R.string.devices, Lucide.MonitorSmartphone),
    Destination("profile", R.string.profile, Lucide.UserRound),
    Destination("settings", R.string.settings, Lucide.Settings),
)

@Composable
fun ValkyApp(main: MainViewModel) {
    val paired by main.paired.collectAsStateWithLifecycle()
    val actionBusy by main.actionBusy.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!paired) {
                OnboardingScreen(main)
            } else {
                ConnectedValkyApp(main)
            }
            ToastMessageHost(
                notices = main.notices,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(14.dp),
            )
            if (actionBusy) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
            }
        }
    }
}

@Composable
private fun ConnectedValkyApp(main: MainViewModel) {
    val nav = rememberNavController()
    val admin by main.admin.collectAsStateWithLifecycle()
    val eventsViewModel: com.ferforastieri.valky.feature.events.EventsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val events by eventsViewModel.events.collectAsStateWithLifecycle()
    val unreadNotifications = events.count { it.acknowledgedAt == null }
    val update by main.updateInfo.collectAsStateWithLifecycle()
    val updating by main.updating.collectAsStateWithLifecycle()
    var updateDismissed by remember { mutableStateOf(false) }
    // The notice is dismissible but re-opens every time the app is opened.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        updateDismissed = false
        // Entrar no aplicativo já marca as notificações como vistas.
        eventsViewModel.markAllSeen()
    }
    StartupAlertPermissions()
    update?.takeIf { !updateDismissed }?.let { info ->
        UpdateAvailableSheet(
            version = info.latestVersion,
            updating = updating,
            onUpdate = { updateDismissed = true; main.startUpdate() },
            onDismiss = { updateDismissed = true },
        )
    }
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val selectedIndex = destinations.indexOfFirst { it.route == currentRoute }
    val showBottomBar = selectedIndex >= 0 || currentRoute == "events" || currentRoute == "event/{id}" || currentRoute == "camera/{id}" || currentRoute == "lights" || currentRoute == "light/{id}" || currentRoute == "remote/{id}" || currentRoute == "person/{id}"
    val showTopBar = selectedIndex >= 0 || currentRoute == "events" || currentRoute == "event/{id}" || currentRoute == "lights"
    val pendingEvent by main.pendingEvent.collectAsStateWithLifecycle()
    val pendingCamera by main.pendingCamera.collectAsStateWithLifecycle()
    val pendingSection by main.pendingSection.collectAsStateWithLifecycle()
    val refreshing by main.refreshing.collectAsStateWithLifecycle()
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
    // A home screen widget can send the user straight to lights or to a remote.
    LaunchedEffect(pendingSection) {
        pendingSection?.let { section ->
            when {
                section == "lights" -> nav.navigate("lights") { launchSingleTop = true }
                // O widget de lâmpadas abre a lâmpada tocada, não a lista.
                section.startsWith("light:") -> nav.navigate("light/${section.substringAfter("light:")}") { launchSingleTop = true }
                section.startsWith("remote:") -> nav.navigate("remote/${section.substringAfter("remote:")}") { launchSingleTop = true }
                section == "devices" -> nav.openTopLevel("devices")
            }
            main.sectionOpened()
        }
    }
        // Puxar de cima para baixo atualiza tudo, em qualquer tela do app.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { main.refreshEverything() },
            modifier = Modifier.fillMaxSize(),
        ) {
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                val title = when (currentRoute) {
                    "devices", "cameras" -> stringResource(R.string.devices)
                    "people" -> stringResource(R.string.location_screen)
                    "profile" -> stringResource(R.string.profile)
                    "settings" -> stringResource(R.string.settings)
                    "events", "event/{id}" -> stringResource(R.string.events)
                    "lights" -> stringResource(R.string.lighting)
                    else -> stringResource(R.string.overview)
                }
                ValkyTopBar(
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
        val contentModifier = Modifier.padding(padding)
        NavHost(
            navController = nav,
            startDestination = "overview",
            modifier = contentModifier,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("overview") {
                OverviewScreen(
                    onCamera = { nav.navigate("camera/$it") },
                    onEvent = { nav.navigate("event/$it") },
                    onCameras = { nav.openTopLevel("devices") },
                    onPeople = { nav.openTopLevel("people") },
                    onEvents = { nav.navigate("events") { launchSingleTop = true } },
                    onRemote = { nav.navigate("remote/$it") },
                    onLight = { nav.navigate("light/$it") },
                    onFavorite = { favorite ->
                        when (favorite.type) {
                            "camera" -> nav.navigate("camera/${favorite.id}")
                            "remote" -> nav.navigate("remote/${favorite.id}")
                            else -> nav.navigate("lights") { launchSingleTop = true }
                        }
                    },
                )
            }
            composable("lights") { LightsScreen(admin = admin, onLight = { nav.navigate("light/$it") }) }
            composable("light/{id}") {
                LightDetailScreen(
                    lightId = it.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onNotifications = { nav.navigate("events") { launchSingleTop = true } },
                    unreadNotifications = unreadNotifications,
                )
            }
            composable("devices") { DevicesScreen(admin = admin, onCamera = { nav.navigate("camera/$it") }, onRemote = { nav.navigate("remote/$it") }, onLight = { nav.navigate("light/$it") }) }
            composable("cameras") { DevicesScreen(admin = admin, onCamera = { nav.navigate("camera/$it") }, onRemote = { nav.navigate("remote/$it") }, onLight = { nav.navigate("light/$it") }, initialPage = 0) }
            composable("camera/{id}") {
                CameraLiveScreen(
                    cameraId = it.arguments?.getString("id").orEmpty(),
                    onEvent = { id -> nav.navigate("event/$id") },
                    onBack = { nav.popBackStack() },
                    onNotifications = { nav.navigate("events") { launchSingleTop = true } },
                    unreadNotifications = unreadNotifications,
                )
            }
            composable("remote/{id}") {
                RemoteControlScreen(
                    onBack = { nav.popBackStack() },
                    onNotifications = { nav.navigate("events") { launchSingleTop = true } },
                    unreadNotifications = unreadNotifications,
                )
            }
            composable("events") {
                EventsScreen(
                    onEvent = { nav.navigate("event/$it") },
                    onCamera = { nav.navigate("camera/$it") },
                    vm = eventsViewModel,
                )
            }
            composable("event/{id}") { EventDetailScreen() }
            composable("people") { PeopleScreen(onPerson = { nav.navigate("person/$it") }) }
            composable("person/{id}") { PersonDetailScreen(onBack = { nav.popBackStack() }) }
            composable("profile") { ProfileScreen(admin = admin) }
            composable("settings") { SettingsScreen(main) }
        }
    }
        }
}

internal fun NavHostController.openTopLevel(route: String) {
    if (route == "devices" && currentDestination?.route == "camera/{id}") {
        if (popBackStack("devices", inclusive = false)) return
    }
    if (route == "overview") {
        if (!popBackStack("overview", inclusive = false) && currentDestination?.route != "overview") {
            navigate("overview") { launchSingleTop = true }
        }
        return
    }
    navigate(route) {
        // Restore only the requested destination, never its former notification overlay.
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
        restoreState = false
    }
}
