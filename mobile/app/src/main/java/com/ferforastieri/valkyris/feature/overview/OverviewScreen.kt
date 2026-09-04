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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Video
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.database.EventEntity
import com.ferforastieri.valkyris.core.model.Camera as CameraModel
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class OverviewState(val cameras: List<CameraModel> = emptyList(), val loading: Boolean = true)

@HiltViewModel
class OverviewViewModel @Inject constructor(private val repository: ValkyrisRepository) : ViewModel() {
    private val _state = MutableStateFlow(OverviewState())
    val state = _state.asStateFlow()
    val events = repository.cachedEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rules = repository.cachedRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _state.value = OverviewState(runCatching { repository.api.cameras() }.getOrDefault(emptyList()), false)
            runCatching { repository.refreshEvents() }
            runCatching { repository.refreshRules() }
        }
    }
}

@Composable
fun OverviewScreen(
    onCamera: (String) -> Unit,
    onEvent: (String) -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val events = viewModel.events.collectAsStateWithLifecycle().value
    val rules = viewModel.rules.collectAsStateWithLifecycle().value
    OverviewContent(state.cameras, rules.count { it.enabled }, events, onCamera, onEvent)
}

@Composable
fun OverviewContent(
    cameras: List<CameraModel>,
    activeRules: Int,
    events: List<EventEntity>,
    onCamera: (String) -> Unit = {},
    onEvent: (String) -> Unit = {},
) {
    val pending = events.count { it.acknowledgedAt == null }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(Lucide.Camera, cameras.size.toString(), stringResource(R.string.cameras), Modifier.weight(1f), accent = true)
                MetricCard(Lucide.SlidersHorizontal, activeRules.toString(), stringResource(R.string.rules), Modifier.weight(1f))
                MetricCard(Lucide.Bell, pending.toString(), stringResource(R.string.pending_alerts), Modifier.weight(1f), pending > 0)
            }
        }
        if (cameras.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.cameras)) }
            items(cameras.take(2), key = { "camera-${it.id}" }) { camera ->
                Card(
                    onClick = { onCamera(camera.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(42.dp), RoundedCornerShape(13.dp), color = com.ferforastieri.valkyris.core.design.ColorTokens.BrandTile) {
                            Icon(Lucide.Camera, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(camera.name, fontWeight = FontWeight.SemiBold)
                            Text(camera.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.recent_activity)) }
        if (events.isEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(stringResource(R.string.no_events_body), Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(events.take(3), key = { "event-${it.id}" }) { event ->
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
                            Text(event.type.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Medium)
                            Text(overviewTime(event.occurredAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${(event.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier, alarm: Boolean = false, accent: Boolean = false) {
    Surface(modifier, RoundedCornerShape(17.dp), MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = when { alarm -> MaterialTheme.colorScheme.error; accent -> MaterialTheme.colorScheme.secondary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable private fun SectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
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
