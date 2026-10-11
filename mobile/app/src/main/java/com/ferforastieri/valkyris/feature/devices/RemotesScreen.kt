package com.ferforastieri.valkyris.feature.devices

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Radar
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.Volume1
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.RemoteDevice
import com.ferforastieri.valkyris.core.model.RemoteInput

@Composable
fun RemotesScreen(admin: Boolean, vm: RemotesViewModel = hiltViewModel()) {
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    var removingId by remember { mutableStateOf<String?>(null) }
    val editing = state.remotes.firstOrNull { it.id == editingId }
    val open = state.remotes.firstOrNull { it.id == openId }
    val removing = state.remotes.firstOrNull { it.id == removingId }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.remotes.isEmpty() && !state.loading) EmptyRemotes()
            if (state.loading && state.remotes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            ) {
                items(state.remotes, key = { it.id }) { remote ->
                    RemoteCard(
                        remote = remote,
                        busy = remote.id in state.busy || remote.id in state.pairing,
                        onOpen = { openId = remote.id },
                        onEdit = if (admin) ({ editingId = remote.id }) else null,
                        onDelete = if (admin) ({ removingId = remote.id }) else null,
                    )
                }
            }
        }
        if (admin) FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp),
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) { Icon(Lucide.Plus, stringResource(R.string.add_remote)) }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    if (showAdd) RemoteEditorSheet(
        saving = false,
        discovering = state.discovering,
        discovered = state.discovered,
        onDiscover = { vm.discover(context) },
        onPick = { tv ->
            showAdd = false
            vm.addAndPair(com.ferforastieri.valkyris.core.model.RemoteInput(name = tv.name, host = tv.host, port = 8002))
        },
        onDismiss = { showAdd = false },
        onSave = { input -> vm.add(input) { if (it) showAdd = false } },
    )
    editing?.let { remote ->
        RemoteEditorSheet(
            initial = remote,
            saving = false,
            onDismiss = { editingId = null },
            onSave = { input -> vm.update(remote.id, com.ferforastieri.valkyris.core.model.RemoteUpdate(name = input.name, host = input.host, port = input.port)) { if (it) editingId = null } },
        )
    }
    open?.let { remote ->
        RemoteControlSheet(
            remote = remote,
            busy = remote.id in state.busy,
            pairing = remote.id in state.pairing,
            onSend = { key -> vm.send(remote.id, key) },
            onPair = { vm.pair(remote.id) },
            onDismiss = { openId = null; vm.clearError() },
        )
    }
    removing?.let { remote ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text(stringResource(R.string.remote_remove_title)) },
            text = { Text(stringResource(R.string.remote_remove_body, remote.name)) },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                Button(
                    onClick = { vm.remove(remote.id) { removingId = null } },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.remove)) }
            },
        )
    }
}

@Composable
private fun EmptyRemotes() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 26.dp, vertical = 24.dp).width(300.dp),
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
}

@Composable
private fun RemoteCard(
    remote: RemoteDevice,
    busy: Boolean,
    onOpen: () -> Unit,
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
            onEdit?.let { IconButton(onClick = it) { Icon(Lucide.Pencil, stringResource(R.string.remote_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
            onDelete?.let { IconButton(onClick = it) { Icon(Lucide.Trash2, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun RemoteEditorSheet(
    initial: RemoteDevice? = null,
    saving: Boolean,
    discovering: Boolean = false,
    discovered: List<DiscoveredTv> = emptyList(),
    onDiscover: () -> Unit = {},
    onPick: (DiscoveredTv) -> Unit = {},
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
            if (initial == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.remote_discover), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.remote_discover_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onDiscover, enabled = !discovering) {
                        if (discovering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Lucide.Radar, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (discovering) R.string.remote_discovering else R.string.remote_discover_action))
                    }
                }
                when {
                    discovering -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    discovered.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        discovered.forEach { tv ->
                            Surface(
                                onClick = { onPick(tv) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Lucide.Tv, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(tv.name, fontWeight = FontWeight.SemiBold)
                                        Text(tv.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Lucide.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                        Text(stringResource(R.string.remote_discover_connect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text(stringResource(R.string.remote_discover_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_name)) }, singleLine = true)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_host)) }, supportingText = { Text(stringResource(R.string.remote_host_hint)) }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_port)) }, singleLine = true)
            Text(stringResource(R.string.remote_samsung_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RemoteControlSheet(
    remote: RemoteDevice,
    busy: Boolean,
    pairing: Boolean,
    onSend: (String) -> Unit,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enabled = remote.tokenConfigured && !pairing
    ValkyrisBottomSheet(
        title = remote.name,
        onDismiss = onDismiss,
        dismissEnabled = !busy && !pairing,
        actions = { TextButton(onClick = onDismiss, enabled = !pairing) { Text(stringResource(R.string.close)) } },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!remote.tokenConfigured) {
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.remote_pair_required), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.remote_pair_hint), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onPair, enabled = !pairing) {
                            Icon(Lucide.Link, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.remote_pair))
                        }
                    }
                }
            }
            if (pairing) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.remote_pairing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (remote.setupError.isNotBlank() && remote.tokenConfigured) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.TriangleAlert, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(remote.setupError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (remote.tokenConfigured) {
                DPad(enabled = enabled, onSend = onSend)
                RemoteRow {
                    RemoteButton(Lucide.Volume2, stringResource(R.string.remote_volume_up), enabled, Modifier.weight(1f)) { onSend("KEY_VOLUP") }
                    RemoteButton(Lucide.VolumeX, stringResource(R.string.remote_mute), enabled, Modifier.weight(1f)) { onSend("KEY_MUTE") }
                    RemoteButton(Lucide.Volume1, stringResource(R.string.remote_volume_down), enabled, Modifier.weight(1f)) { onSend("KEY_VOLDOWN") }
                }
                RemoteRow {
                    RemoteButton(Lucide.ChevronUp, stringResource(R.string.remote_channel_up), enabled, Modifier.weight(1f)) { onSend("KEY_CHUP") }
                    RemoteButton(Lucide.ChevronDown, stringResource(R.string.remote_channel_down), enabled, Modifier.weight(1f)) { onSend("KEY_CHDOWN") }
                    RemoteButton(Lucide.List, stringResource(R.string.remote_channel_list), enabled, Modifier.weight(1f)) { onSend("KEY_CH_LIST") }
                }
                RemoteRow {
                    RemoteButton(Lucide.SkipBack, stringResource(R.string.remote_rewind), enabled, Modifier.weight(1f)) { onSend("KEY_REWIND") }
                    RemoteButton(Lucide.Play, stringResource(R.string.remote_play), enabled, Modifier.weight(1f)) { onSend("KEY_PLAY") }
                    RemoteButton(Lucide.Pause, stringResource(R.string.remote_pause), enabled, Modifier.weight(1f)) { onSend("KEY_PAUSE") }
                    RemoteButton(Lucide.SkipForward, stringResource(R.string.remote_forward), enabled, Modifier.weight(1f)) { onSend("KEY_FF") }
                }
                RemoteRow {
                    RemoteButton(Lucide.House, stringResource(R.string.remote_home), enabled, Modifier.weight(1f)) { onSend("KEY_HOME") }
                    RemoteButton(Lucide.ArrowLeft, stringResource(R.string.remote_back), enabled, Modifier.weight(1f)) { onSend("KEY_RETURN") }
                    RemoteButton(Lucide.Menu, stringResource(R.string.remote_menu), enabled, Modifier.weight(1f)) { onSend("KEY_MENU") }
                    RemoteButton(Lucide.Power, stringResource(R.string.remote_power), enabled, Modifier.weight(1f), tint = MaterialTheme.colorScheme.error) { onSend("KEY_POWER") }
                }
                Text(stringResource(R.string.remote_power_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DPad(enabled: Boolean, onSend: (String) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.size(210.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 1.dp,
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                RemoteButton(Lucide.ChevronUp, stringResource(R.string.remote_up), enabled, Modifier.align(Alignment.TopCenter).size(56.dp)) { onSend("KEY_UP") }
                RemoteButton(Lucide.ChevronDown, stringResource(R.string.remote_down), enabled, Modifier.align(Alignment.BottomCenter).size(56.dp)) { onSend("KEY_DOWN") }
                RemoteButton(Lucide.ChevronLeft, stringResource(R.string.remote_left), enabled, Modifier.align(Alignment.CenterStart).size(56.dp)) { onSend("KEY_LEFT") }
                RemoteButton(Lucide.ChevronRight, stringResource(R.string.remote_right), enabled, Modifier.align(Alignment.CenterEnd).size(56.dp)) { onSend("KEY_RIGHT") }
                Surface(
                    onClick = { onSend("KEY_ENTER") },
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.Center).size(78.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.remote_ok), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun RemoteRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun RemoteButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, label, Modifier.size(20.dp), tint = tint ?: MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
