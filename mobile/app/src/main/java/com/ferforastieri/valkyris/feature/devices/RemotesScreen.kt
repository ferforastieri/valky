package com.ferforastieri.valkyris.feature.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Radar
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Tv
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.RemoteDevice
import com.ferforastieri.valkyris.core.model.RemoteInput
import com.ferforastieri.valkyris.core.model.RemoteUpdate

@Composable
fun RemotesScreen(admin: Boolean, onRemote: (String) -> Unit = {}, vm: RemotesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    // Entering the listing already starts looking for TVs on the network.
    LaunchedEffect(Unit) { vm.discover(context) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf<String?>(null) }
    var removingId by remember { mutableStateOf<String?>(null) }
    var manualAdd by remember { mutableStateOf(false) }
    val editing = state.remotes.firstOrNull { it.id == editingId }
    val removing = state.remotes.firstOrNull { it.id == removingId }
    // A TV already registered must not be offered for pairing again.
    val discovered = state.discovered.filter { tv -> state.remotes.none { it.host == tv.host } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            state.error?.let { /* errors surface as a toast */ }
            if (admin) {
                DiscoveryHeader(
                    discovering = state.discovering,
                    found = state.discovered.size,
                    onRetry = { vm.discover(context) },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            ) {
                if (admin && discovered.isNotEmpty()) {
                    item { Text(stringResource(R.string.remote_discovered_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(discovered, key = { "found-${it.host}" }) { tv ->
                        DiscoveredTvCard(tv, onConnect = { vm.addAndPair(RemoteInput(name = tv.name, host = tv.host, port = 8002)) })
                    }
                    item { HorizontalDivider() }
                }
                if (state.loading && state.remotes.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
                if (state.remotes.isNotEmpty()) {
                    item { Text(stringResource(R.string.remote_my_controls), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(state.remotes, key = { it.id }) { remote ->
                        RemoteCard(
                            remote = remote,
                            busy = remote.id in state.busy || remote.id in state.pairing,
                            onOpen = { onRemote(remote.id) },
                            onFavorite = { vm.toggleFavorite(remote.id, !remote.favorite) },
                            onEdit = if (admin) ({ editingId = remote.id }) else null,
                            onDelete = if (admin) ({ removingId = remote.id }) else null,
                        )
                    }
                }
                if (!state.loading && state.remotes.isEmpty() && (discovered.isEmpty() || !admin)) {
                    item { EmptyRemotes() }
                }
            }
        }
        if (admin) FloatingActionButton(
            onClick = { manualAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp),
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) { Icon(Lucide.Plus, stringResource(R.string.add_remote)) }
    }

    if (manualAdd) RemoteEditorSheet(
        saving = false,
        onDismiss = { manualAdd = false },
        onSave = { input -> vm.add(input) { if (it) manualAdd = false } },
    )
    editing?.let { remote ->
        RemoteEditorSheet(
            initial = remote,
            saving = false,
            onDismiss = { editingId = null },
            onSave = { input -> vm.update(remote.id, RemoteUpdate(name = input.name, host = input.host, port = input.port)) { if (it) editingId = null } },
        )
    }
    removing?.let { remote ->
        ValkyrisBottomSheet(
            title = stringResource(R.string.remote_remove_title),
            onDismiss = { removingId = null },
            actions = {
                TextButton(onClick = { removingId = null }) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { vm.remove(remote.id) { removingId = null } },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.remove)) }
            },
        ) {
            Text(stringResource(R.string.remote_remove_body, remote.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiscoveryHeader(discovering: Boolean, found: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Lucide.Radar, null, Modifier.padding(9.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    discovering -> stringResource(R.string.remote_searching)
                    found > 0 -> stringResource(R.string.remote_found_count, found)
                    else -> stringResource(R.string.remote_discover_empty)
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry, enabled = !discovering) { Text(stringResource(R.string.remote_discover_action)) }
        }
        if (discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun DiscoveredTvCard(tv: DiscoveredTv, onConnect: () -> Unit) {
    Surface(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = .35f)),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
                Icon(Lucide.Tv, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondary)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(tv.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(tv.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Icon(Lucide.Link, stringResource(R.string.remote_pair), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun EmptyRemotes() = Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(Lucide.Tv, null, Modifier.padding(13.dp).size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(stringResource(R.string.no_remotes), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.no_remotes_body), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoteCard(
    remote: RemoteDevice,
    busy: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val ready = remote.setupStatus == "ready" && remote.tokenConfigured
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (ready) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = .4f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Lucide.Tv, null, Modifier.padding(12.dp).size(24.dp), tint = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(remote.name, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        busy -> stringResource(R.string.remote_pairing)
                        ready -> "${remote.host}:${remote.port} · ${stringResource(R.string.remote_paired)}"
                        remote.setupError.isNotBlank() -> remote.setupError
                        else -> "${remote.host}:${remote.port} · ${stringResource(R.string.remote_not_paired)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onFavorite) { Icon(Lucide.Star, stringResource(if (remote.favorite) R.string.unfavorite else R.string.favorite), tint = if (remote.favorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) }
            onEdit?.let { IconButton(onClick = it) { Icon(Lucide.Pencil, stringResource(R.string.remote_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
            onDelete?.let { IconButton(onClick = it) { Icon(Lucide.Trash2, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun RemoteEditorSheet(
    initial: RemoteDevice? = null,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (RemoteInput) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial?.id) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial?.id) { mutableStateOf((initial?.port ?: 8002).toString()) }
    ValkyrisBottomSheet(
        title = stringResource(if (initial == null) R.string.add_remote else R.string.remote_edit),
        onDismiss = onDismiss,
        dismissEnabled = !saving,
        actions = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = { onSave(RemoteInput(name = name.trim(), host = host.trim(), port = port.toIntOrNull() ?: 8002)) },
                enabled = !saving && name.isNotBlank() && host.isNotBlank(),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.save))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_name)) }, singleLine = true)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_host)) }, supportingText = { Text(stringResource(R.string.remote_host_hint)) }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_port)) }, singleLine = true)
            Text(stringResource(R.string.remote_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
