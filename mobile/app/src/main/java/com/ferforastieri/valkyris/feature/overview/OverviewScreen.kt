package com.ferforastieri.valkyris.feature.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.LightbulbOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.UserRound
import com.composables.icons.lucide.Video
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.cameraIcon
import com.ferforastieri.valkyris.core.model.Camera as CameraModel
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.RemoteDevice
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import com.ferforastieri.valkyris.feature.cameras.CameraFailureSheet
import com.ferforastieri.valkyris.feature.events.eventCategoryRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class FavoriteItem(
    val type: String,
    val id: String,
    val name: String,
    val online: Boolean = false,
    val power: Boolean = false,
    val mode: String = "white",
    val hue: Int = 0,
    val saturation: Int = 100,
    val value: Int = 100,
    val kelvin: Int = 4000,
)

data class OverviewState(
    val cameras: List<CameraModel> = emptyList(),
    val remotes: List<RemoteDevice> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(private val repository: ValkyrisRepository) : ViewModel() {
    private val _state = MutableStateFlow(OverviewState())
    val state = _state.asStateFlow()
    val events = repository.events
    val rules = repository.rules
    val people = repository.people

    init {
        viewModelScope.launch {
            combine(repository.cameras, repository.lights, repository.remotes) { cameras, lights, remotes ->
                OverviewState(cameras, remotes, favoritesOf(cameras, lights, remotes), false)
            }.collect { _state.value = it }
        }
        viewModelScope.launch {
            // Pedidos independentes: saem juntos, não um atrás do outro.
            coroutineScope {
                launch { runCatching { repository.refreshCameras() } }
                launch { runCatching { repository.refreshEvents() } }
                launch { runCatching { repository.refreshRules() } }
                launch { runCatching { repository.refreshLights() } }
                launch { runCatching { repository.refreshRemotes() } }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            coroutineScope {
                launch { runCatching { repository.refreshCameras() } }
                launch { runCatching { repository.refreshEvents() } }
                launch { runCatching { repository.refreshRules() } }
                launch { runCatching { repository.refreshUsers() } }
                launch { runCatching { repository.refreshLights() } }
                launch { runCatching { repository.refreshRemotes() } }
            }
        }
    }

    private fun favoritesOf(cameras: List<CameraModel>, lights: List<LightDevice>, remotes: List<RemoteDevice>): List<FavoriteItem> {
        val items = mutableListOf<FavoriteItem>()
        cameras.filter { it.favorite }.forEach { items += FavoriteItem("camera", it.id, it.name) }
        lights.filter { it.favorite }.forEach {
            items += FavoriteItem(
                type = "light", id = it.id, name = it.name,
                online = it.state.online, power = it.state.power,
                mode = it.state.mode, hue = it.state.color.hue, saturation = it.state.color.saturation,
                value = it.state.color.value, kelvin = it.state.temperatureKelvin,
            )
        }
        remotes.filter { it.favorite }.forEach { items += FavoriteItem("remote", it.id, it.name) }
        return items
    }

    /** Turns a favorite bulb on or off without leaving the overview. */
    fun toggleLight(id: String, power: Boolean) {
        viewModelScope.launch {
            runCatching { repository.controlLight(id, com.ferforastieri.valkyris.core.model.LightStatePatch(power = power)) }
        }
    }
}

@Composable
fun OverviewScreen(
    onCamera: (String) -> Unit,
    onEvent: (String) -> Unit,
    onCameras: () -> Unit,
    onPeople: () -> Unit,
    onEvents: () -> Unit,
    onRemote: (String) -> Unit,
    onLight: () -> Unit,
    onFavorite: (FavoriteItem) -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(Unit) { viewModel.refresh(); onPauseOrDispose {} }
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val events = viewModel.events.collectAsStateWithLifecycle().value
    val people = viewModel.people.collectAsStateWithLifecycle().value
    var failedCameraId by remember { mutableStateOf<String?>(null) }
    val failedCamera = state.cameras.firstOrNull { it.id == failedCameraId }
    OverviewContent(
        state.cameras,
        people.count { it.enabled },
        events,
        remotes = state.remotes,
        favorites = state.favorites,
        onCamera = { id ->
            val camera = state.cameras.firstOrNull { it.id == id }
            if (camera?.setupStatus == "failed") failedCameraId = id else onCamera(id)
        },
        onEvent = onEvent,
        onCameras = onCameras,
        onPeople = onPeople,
        onTv = { state.remotes.firstOrNull()?.let { remote -> onRemote(remote.id) } ?: onCameras() },
        onLight = onLight,
        onFavorite = onFavorite,
        onToggleLight = { favorite -> viewModel.toggleLight(favorite.id, !favorite.power) },
        activityChart = { OverviewActivityChart(onEvent = onEvent) },
    )
    failedCamera?.let { CameraFailureSheet(it, onDismiss = { failedCameraId = null }) }
}

@Composable
fun OverviewContent(
    cameras: List<CameraModel>,
    trackedPeople: Int,
    events: List<com.ferforastieri.valkyris.core.model.ValkyrisEvent>,
    remotes: List<RemoteDevice> = emptyList(),
    favorites: List<FavoriteItem> = emptyList(),
    onCamera: (String) -> Unit = {},
    onEvent: (String) -> Unit = {},
    onCameras: () -> Unit = {},
    onPeople: () -> Unit = {},
    onEvents: () -> Unit = {},
    onTv: () -> Unit = {},
    onLight: () -> Unit = {},
    onFavorite: (FavoriteItem) -> Unit = {},
    onToggleLight: (FavoriteItem) -> Unit = {},
    activityChart: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (favorites.isEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(stringResource(R.string.overview_no_favorites), Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            // Uma única seção de favoritos: só a grade, sem rótulo nem ícone.
            // As colunas são escolhidas para as linhas ficarem equilibradas
            // (ex.: 4 vira 2+2; 5 vira 3+2), nunca uma linha com um só controle.
            item(key = "favorites-grid") {
                FavoriteGrid(
                    items = orderedFavorites(favorites),
                    onOpen = { favorite ->
                        when (favorite.type) {
                            "camera" -> onCamera(favorite.id)
                            // A lâmpada é acionada aqui mesmo, sem sair da visão geral.
                            "light" -> onToggleLight(favorite)
                            else -> onFavorite(favorite)
                        }
                    },
                )
            }
        }
        item(key = "activity-label") { SectionLabel(stringResource(R.string.recent_activity)) }
        item { activityChart() }
        if (events.isEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(stringResource(R.string.no_events_body), Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(events.take(3), key = { "event-${it.id}" }) { event -> RecentEventCard(event, onEvent) }
        }
    }
}

@Composable
private fun RecentEventCard(event: com.ferforastieri.valkyris.core.model.ValkyrisEvent, onEvent: (String) -> Unit) {
    Card(
        onClick = { onEvent(event.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(11.dp),
                color = if (event.acknowledgedAt == null) MaterialTheme.colorScheme.error.copy(alpha = .12f) else MaterialTheme.colorScheme.secondary.copy(alpha = .15f),
            ) {
                Icon(
                    overviewEventIcon(event.type),
                    null,
                    Modifier.padding(8.dp),
                    tint = if (event.acknowledgedAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(com.ferforastieri.valkyris.feature.events.eventTitle(event), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(overviewTime(event.occurredAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Bateria e localização não têm "confiança": só detecções mostram o
            // percentual.
            if (eventCategoryRes(event) != R.string.event_category_location) {
                Text("${(event.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}


private fun favoriteIcon(type: String): ImageVector = when (type) {
    "camera" -> Lucide.Video
    "light" -> Lucide.Lightbulb
    else -> Lucide.Tv
}

@Composable
private fun MetricCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier, onClick: () -> Unit, alarm: Boolean = false, accent: Boolean = false) {
    val tint = when {
        alarm -> MaterialTheme.colorScheme.error
        accent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .14f)) {
                Icon(icon, null, Modifier.padding(8.dp).size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// Ordem dos favoritos na visão geral: câmeras, luzes e controles.
private val favoriteOrder = listOf("camera", "light", "remote")

internal fun orderedFavorites(favorites: List<FavoriteItem>): List<FavoriteItem> =
    favoriteOrder.flatMap { type -> favorites.filter { it.type == type } }

/**
 * Cartões favoritos em grade única. As colunas são calculadas para as linhas
 * ficarem equilibradas (4 → 2+2, 5 → 3+2, 6 → 3+3), sempre com cartões do mesmo
 * tamanho. Com fonte ampliada vira uma coluna só.
 */
@Composable
internal fun FavoriteGrid(
    items: List<FavoriteItem>,
    onOpen: (FavoriteItem) -> Unit,
) {
    val count = items.size
    val columns = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { favorite ->
                    FavoriteCard(
                        item = favorite,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(favorite) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun FavoriteCard(item: FavoriteItem, modifier: Modifier, onClick: () -> Unit) {
    val lamp = item.type == "light"
    val lit = lamp && item.power && item.online
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(36.dp), RoundedCornerShape(11.dp), color = com.ferforastieri.valkyris.core.design.ColorTokens.BrandTile) {
                if (lamp) {
                    com.ferforastieri.valkyris.core.design.AnimatedBulbIcon(
                        on = lit,
                        color = com.ferforastieri.valkyris.core.design.lightTint(item.mode, item.hue, item.saturation, item.value, item.kelvin),
                        offTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(7.dp),
                    )
                } else {
                    Icon(favoriteIcon(item.type), null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.width(11.dp))
            Text(
                item.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (lamp && !item.online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary) }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun overviewTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun overviewEventIcon(type: String) = when {
    type.contains("mov", ignoreCase = true) || type.contains("motion", ignoreCase = true) -> Lucide.Video
    type.contains("camp", ignoreCase = true) || type.contains("door", ignoreCase = true) -> Lucide.Bell
    else -> Lucide.Mic
}
