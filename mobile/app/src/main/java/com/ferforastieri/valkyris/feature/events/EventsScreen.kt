package com.ferforastieri.valkyris.feature.events

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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.ListFilter
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.database.EventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EventsScreen(onEvent: (String) -> Unit = {}, onCamera: (String) -> Unit = {}, vm: EventsViewModel = hiltViewModel()) {
    val events = vm.events.collectAsStateWithLifecycle().value
    EventsContent(events, onEvent, onCamera, onAcknowledge = vm::acknowledge, onAcknowledgeAll = vm::acknowledgeAll)
}

@Composable
fun EventsContent(
    events: List<EventEntity>,
    onEvent: (String) -> Unit = {},
    onCamera: (String) -> Unit = {},
    onAcknowledge: (EventEntity) -> Unit = {},
    onAcknowledgeAll: () -> Unit = {},
) {
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    val hasUnread = events.any { it.acknowledgedAt == null }
    val visibleEvents = if (unreadOnly) events.filter { it.acknowledgedAt == null } else events
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !unreadOnly,
                    onClick = { unreadOnly = false },
                    label = { Text(stringResource(R.string.all_notifications)) },
                    leadingIcon = { Icon(Lucide.ListFilter, null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = unreadOnly,
                    onClick = { unreadOnly = true },
                    label = { Text(stringResource(R.string.unread_notifications)) },
                    leadingIcon = { Icon(Lucide.Eye, null, Modifier.size(16.dp)) },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAcknowledgeAll, enabled = hasUnread) {
                Icon(Lucide.Check, contentDescription = stringResource(R.string.mark_all_read))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (visibleEvents.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Lucide.BellOff, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(if (unreadOnly) R.string.no_unread_notifications else R.string.no_events), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(if (unreadOnly) R.string.no_unread_notifications_body else R.string.no_events_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(visibleEvents, key = { it.id }) { event ->
                    EventCard(event, onOpen = { onEvent(event.id) }, onCamera = { onCamera(event.cameraId) }, onAck = { onAcknowledge(event) })
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventEntity, onOpen: () -> Unit, onCamera: () -> Unit, onAck: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(
                    if (event.acknowledgedAt == null) MaterialTheme.colorScheme.error.copy(alpha = .12f)
                    else MaterialTheme.colorScheme.secondary.copy(alpha = .15f),
                    MaterialTheme.shapes.small,
                ),
            ) {
                Icon(
                    eventIcon(event.type),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp).align(Alignment.Center),
                    tint = if (event.acknowledgedAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(com.ferforastieri.valkyris.core.model.detectorLabelRes(event.type)), fontWeight = FontWeight.SemiBold)
                Text(formatTime(event.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(event.confidence * 100).toInt()}% confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onCamera) { Icon(Lucide.Video, stringResource(R.string.open_camera)) }
                if (event.acknowledgedAt == null) IconButton(onAck) { Icon(Lucide.Check, stringResource(R.string.acknowledge)) }
            }
        }
    }
}

private fun eventIcon(type: String) = when {
    type.contains("mov", ignoreCase = true) || type.contains("motion", ignoreCase = true) -> Lucide.Video
    type.contains("camp", ignoreCase = true) || type.contains("door", ignoreCase = true) -> Lucide.Bell
    else -> Lucide.Mic
}

private fun formatTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
