package com.ferforastieri.valkyris.feature.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class FavoriteItem(val type: String, val id: String, val name: String, val subtitle: String)

data class OverviewState(
    val cameras: List<CameraModel> = emptyList(),
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
                OverviewState(cameras, favoritesOf(cameras, lights, remotes), false)
            }.collect { _state.value = it }
        }
        viewModelScope.launch {
            runCatching { repository.refreshCameras() }
            runCatching { repository.refreshEvents() }
            runCatching { repository.refreshRules() }
            runCatching { repository.refreshLights() }
            runCatching { repository.refreshRemotes() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.refreshCameras() }
            runCatching { repository.refreshEvents() }
            runCatching { repository.refreshRules() }
            runCatching { repository.refreshUsers() }
            runCatching { repository.refreshLights() }
            runCatching { repository.refreshRemotes() }
        }
    }

    private fun favoritesOf(cameras: List<CameraModel>, lights: List<LightDevice>, remotes: List<RemoteDevice>): List<FavoriteItem> {
        val items = mutableListOf<FavoriteItem>()
        cameras.filter { it.favorite }.forEach { items += FavoriteItem("camera", it.id, it.name, it.host) }
        lights.filter { it.favorite }.forEach { items += FavoriteItem("light", it.id, it.name, it.room.ifBlank { "Iluminação" }) }
        remotes.filter { it.favorite }.forEach { items += FavoriteItem("remote", it.id, it.name, it.host) }
        return items
    }
}

@Composable
fun OverviewScreen(
    onCamera: (String) -> Unit,
    onEvent: (String) -> Unit,
    onCameras: () -> Unit,
    onPeople: () -> Unit,
    onEvents: () -> Unit,
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
        favorites = state.favorites,
        onCamera = { id ->
            val camera = state.cameras.firstOrNull { it.id == id }
            if (camera?.setupStatus == "failed") failedCameraId = id else onCamera(id)
        },
        onEvent = onEvent,
        onCameras = onCameras,
        onPeople = onPeople,
        onEvents = onEvents,
        onLight = onLight,
        onFavorite = onFavorite,
        activityChart = { OverviewActivityChart(onEvent = onEvent) },
    )
    failedCamera?.let { CameraFailureSheet(it, onDismiss = { failedCameraId = null }) }
}

@Composable
fun OverviewContent(
    cameras: List<CameraModel>,
    trackedPeople: Int,
    events: List<com.ferforastieri.valkyris.core.model.ValkyrisEvent>,
    favorites: List<FavoriteItem> = emptyList(),
    onCamera: (String) -> Unit = {},
    onEvent: (String) -> Unit = {},
    onCameras: () -> Unit = {},
    onPeople: () -> Unit = {},
    onEvents: () -> Unit = {},
    onLight: () -> Unit = {},
    onFavorite: (FavoriteItem) -> Unit = {},
    activityChart: @Composable () -> Unit = {},
) {
    val pending = events.count { it.acknowledgedAt == null }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            com.ferforastieri.valkyris.core.design.AdaptiveRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(Lucide.Camera, cameras.size.toString(), stringResource(R.string.cameras), Modifier.weight(1f), onClick = onCameras, accent = true)
                MetricCard(Lucide.UserRound, trackedPeople.toString(), stringResource(R.string.people), Modifier.weight(1f), onClick = onPeople)
                MetricCard(Lucide.Bell, pending.toString(), stringResource(R.string.pending_alerts), Modifier.weight(1f), onClick = onEvents, alarm = pending > 0)
            }
        }
        item { activityChart() }
        item { SectionLabel(stringResource(R.string.overview_favorites)) }
        if (favorites.isEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(stringResource(R.string.overview_no_favorites), Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            items(favorites, key = { "${it.type}-${it.id}" }) { favorite ->
                FavoriteCard(favorite, onCamera = onCamera, onLight = onLight, onFavorite = onFavorite)
            }
        }
    }
}

@Composable
private fun FavoriteCard(item: FavoriteItem, onCamera: (String) -> Unit, onLight: () -> Unit, onFavorite: (FavoriteItem) -> Unit) {
    val tint = when (item.type) {
        "camera" -> MaterialTheme.colorScheme.secondary
        "light" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.secondary
    }
    Card(
        onClick = {
            when (item.type) {
                "camera" -> onCamera(item.id)
                "light" -> onLight()
                else -> onFavorite(item)
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(40.dp), RoundedCornerShape(12.dp), color = com.ferforastieri.valkyris.core.design.ColorTokens.BrandTile) {
                Icon(favoriteIcon(item.type), null, Modifier.padding(10.dp), tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
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
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
