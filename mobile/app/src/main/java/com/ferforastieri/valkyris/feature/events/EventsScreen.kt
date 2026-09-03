package com.ferforastieri.valkyris.feature.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.database.EventEntity
import com.ferforastieri.valkyris.core.design.OperationalHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EventsScreen(onEvent: (String) -> Unit = {}, vm: EventsViewModel = hiltViewModel()) {
    val events = vm.events.collectAsStateWithLifecycle().value
    val pending = events.count { it.acknowledgedAt == null }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        OperationalHeader(
            icon = Lucide.Bell,
            eyebrow = stringResource(R.string.recent_activity),
            title = stringResource(R.string.events),
            metric = pending.toString(),
            status = stringResource(R.string.unread_status),
        )
        Spacer(Modifier.height(18.dp))
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Lucide.BellOff, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.no_events), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.no_events_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    EventCard(event, onOpen = { onEvent(event.id) }, onAck = { vm.acknowledge(event) })
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: EventEntity, onOpen: () -> Unit, onAck: () -> Unit) {
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
                Box(
                    Modifier.size(9.dp).background(
                        if (event.acknowledgedAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        CircleShape,
                    ).align(Alignment.Center),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.type.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                Text(formatTime(event.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(event.confidence * 100).toInt()}% confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.acknowledgedAt == null) {
                IconButton(onAck) { Icon(Lucide.Check, stringResource(R.string.acknowledge)) }
            }
        }
    }
}

private fun formatTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
