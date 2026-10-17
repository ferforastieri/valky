package com.ferforastieri.valky.feature.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.BellOff
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ListFilter
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Video
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ferforastieri.valky.R
import com.ferforastieri.valky.core.design.ValkyBottomSheet
import com.ferforastieri.valky.core.model.ValkyEvent
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EventsScreen(onEvent: (String) -> Unit, onCamera: (String) -> Unit, vm: EventsViewModel = hiltViewModel()) {
    // O canal das notificações só fica de pé com a tela à vista.
    LifecycleResumeEffect(Unit) { vm.setActive(true); onPauseOrDispose { vm.setActive(false) } }
    val events by vm.events.collectAsStateWithLifecycle()
    EventsContent(events, onEvent, onCamera)
}

/**
 * Notificações. Tudo o que chega é marcado como visto ao abrir o aplicativo, sem
 * precisar tocar em check nenhum; aqui sobram só os filtros por tipo, dentro de
 * uma folha para não ocupar a tela.
 */
@Composable
fun EventsContent(
    events: List<ValkyEvent>,
    onEvent: (String) -> Unit = {},
    onCamera: (String) -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf<Int?>(null) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    val visibleEvents = events.filter { category == null || eventCategoryRes(it) == category }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.events), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    onClick = { filtering = true },
                    shape = CircleShape,
                    color = if (category != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.ListFilter, null, Modifier.size(18.dp), tint = if (category != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            category?.let { stringResource(it) } ?: stringResource(R.string.filter_all_types),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (category != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (visibleEvents.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Lucide.BellOff, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.no_events), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.no_events_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visibleEvents, key = { it.id }) { event ->
                        EventCard(event, onOpen = { onEvent(event.id) }, onCamera = { onCamera(event.cameraId) })
                    }
                }
            }
        }
    }
    if (filtering) {
        ValkyBottomSheet(
            title = stringResource(R.string.filter_notifications),
            onDismiss = { filtering = false },
            actions = {
                TextButton(onClick = { category = null; filtering = false }) { Text(stringResource(R.string.filter_clear)) }
                Button(onClick = { filtering = false }) { Text(stringResource(R.string.close)) }
            },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterRow(stringResource(R.string.filter_all_types), category == null) { category = null; filtering = false }
                listOf(R.string.event_category_camera, R.string.event_category_audio, R.string.event_category_location).forEach { item ->
                    FilterRow(stringResource(item), category == item) { category = item; filtering = false }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (selected) Icon(Lucide.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun EventCard(event: ValkyEvent, onOpen: () -> Unit, onCamera: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = .15f), MaterialTheme.shapes.small)) {
                Icon(eventIcon(event.type), null, Modifier.size(21.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(eventTitle(event), fontWeight = FontWeight.SemiBold)
                Text(formatTime(event.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(eventCategoryRes(event)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.cameraId.isNotBlank()) IconButton(onCamera) { Icon(Lucide.Video, stringResource(R.string.open_camera)) }
        }
    }
}

private fun eventIcon(type: String) = when {
    type.startsWith("place_") -> Lucide.MapPin
    type.contains("mov", ignoreCase = true) || type.contains("motion", ignoreCase = true) -> Lucide.Video
    type.contains("camp", ignoreCase = true) || type.contains("door", ignoreCase = true) -> Lucide.Bell
    else -> Lucide.Mic
}

private fun formatTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@Composable internal fun eventTitle(event: ValkyEvent): String {
    return com.ferforastieri.valky.core.model.locationEventTitle(
        event.type,
        event.metadata["personName"]?.jsonPrimitive?.contentOrNull,
        event.metadata["placeName"]?.jsonPrimitive?.contentOrNull,
    ) ?: stringResource(com.ferforastieri.valky.core.model.detectorLabelRes(event.type))
}

internal fun eventCategoryRes(event: ValkyEvent): Int = when {
    event.source == "tracking" || event.type.startsWith("place_") -> R.string.event_category_location
    event.type in setOf("baby_cry", "crying", "scream", "glass_break", "smoke_alarm", "fire_alarm", "siren", "doorbell", "knock", "dog_bark", "cough") -> R.string.event_category_audio
    else -> R.string.event_category_camera
}
