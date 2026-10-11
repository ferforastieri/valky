package com.ferforastieri.valkyris.feature.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.Volume1
import com.composables.icons.lucide.Volume2
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.RemoteApp

/**
 * Full-screen remote control, in the shape of a smart-TV remote: an installed
 * apps bar, a central navigation pad flanked by volume and channel rockers, and
 * the playback / navigation keys. It replaces the old bottom sheet so sending a
 * key never reopens anything.
 */
@Composable
fun RemoteControlScreen(onBack: () -> Unit, vm: RemoteControlViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val remote = state.remote
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(
                    remote?.name ?: stringResource(R.string.remote_control),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (remote?.tokenConfigured == true) stringResource(R.string.remote_paired) else stringResource(R.string.remote_not_paired),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remote?.tokenConfigured == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (remote?.tokenConfigured == true) {
                IconButton(onClick = { vm.loadApps() }) {
                    Icon(Lucide.RefreshCw, stringResource(R.string.remote_refresh), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        when {
            remote == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !remote.tokenConfigured -> PairingPanel(pairing = state.pairing, onPair = vm::pair)
            else -> ConnectedRemote(state, vm)
        }
    }
}

@Composable
private fun ConnectedRemote(state: RemoteControlState, vm: RemoteControlViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppsRow(state, vm)
        RemoteBody(enabled = !state.busy && !state.waking, onSend = vm::send)
        WakeRow(waking = state.waking, onWake = vm::wake)
        Text(
            stringResource(R.string.remote_power_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppsRow(state: RemoteControlState, vm: RemoteControlViewModel) {
    when {
        state.appsLoading && state.apps.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.apps.isEmpty() -> TextButton(onClick = { vm.loadApps() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Lucide.RefreshCw, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.remote_apps_load))
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.remote_apps_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.apps, key = { it.id }) { app -> AppChip(app) { vm.launch(app.id) } }
            }
        }
    }
}

@Composable
private fun AppChip(app: RemoteApp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Tv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text(app.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun RemoteBody(enabled: Boolean, onSend: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Rocker(
                top = RockerKeySpec(Lucide.Volume2, stringResource(R.string.remote_volume_up), "KEY_VOLUP"),
                bottom = RockerKeySpec(Lucide.Volume1, stringResource(R.string.remote_volume_down), "KEY_VOLDOWN"),
                enabled = enabled, onSend = onSend,
            )
            DPad(enabled, onSend)
            Rocker(
                top = RockerKeySpec(Lucide.ChevronUp, stringResource(R.string.remote_channel_up), "KEY_CHUP"),
                bottom = RockerKeySpec(Lucide.ChevronDown, stringResource(R.string.remote_channel_down), "KEY_CHDOWN"),
                enabled = enabled, onSend = onSend,
            )
        }
        RemoteRow {
            RoundKey(Lucide.Power, stringResource(R.string.remote_power_off), enabled, Modifier.weight(1f), tint = MaterialTheme.colorScheme.error) { onSend("KEY_POWER") }
            RoundKey(Lucide.House, stringResource(R.string.remote_home), enabled, Modifier.weight(1f)) { onSend("KEY_HOME") }
            RoundKey(Lucide.ArrowLeft, stringResource(R.string.remote_back), enabled, Modifier.weight(1f)) { onSend("KEY_RETURN") }
            RoundKey(Lucide.Menu, stringResource(R.string.remote_menu), enabled, Modifier.weight(1f)) { onSend("KEY_MENU") }
            RoundKey(Lucide.Play, stringResource(R.string.remote_play), enabled, Modifier.weight(1f)) { onSend("KEY_PLAY") }
        }
    }
}

private data class RockerKeySpec(val icon: ImageVector, val label: String, val key: String)

@Composable
private fun Rocker(top: RockerKeySpec, bottom: RockerKeySpec, enabled: Boolean, onSend: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RockerKey(top, enabled, onSend)
            RockerKey(bottom, enabled, onSend)
        }
    }
}

@Composable
private fun RockerKey(spec: RockerKeySpec, enabled: Boolean, onSend: (String) -> Unit) {
    Surface(
        onClick = { onSend(spec.key) },
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(spec.icon, spec.label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun DPad(enabled: Boolean, onSend: (String) -> Unit) {
    Surface(
        Modifier.size(190.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            PadKey(Lucide.ChevronUp, stringResource(R.string.remote_up), enabled, Modifier.align(Alignment.TopCenter)) { onSend("KEY_UP") }
            PadKey(Lucide.ChevronDown, stringResource(R.string.remote_down), enabled, Modifier.align(Alignment.BottomCenter)) { onSend("KEY_DOWN") }
            PadKey(Lucide.ChevronLeft, stringResource(R.string.remote_left), enabled, Modifier.align(Alignment.CenterStart)) { onSend("KEY_LEFT") }
            PadKey(Lucide.ChevronRight, stringResource(R.string.remote_right), enabled, Modifier.align(Alignment.CenterEnd)) { onSend("KEY_RIGHT") }
            Surface(
                onClick = { onSend("KEY_ENTER") },
                enabled = enabled,
                modifier = Modifier.align(Alignment.Center).size(76.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.remote_ok), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun PadKey(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(50.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun RemoteRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, tint: Color? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(20.dp), tint = tint ?: MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun WakeRow(waking: Boolean, onWake: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onWake, enabled = !waking, modifier = Modifier.fillMaxWidth()) {
            if (waking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Lucide.Power, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(R.string.remote_wake))
        }
        Text(
            stringResource(R.string.remote_wake_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingPanel(pairing: Boolean, onPair: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Lucide.Tv, null, Modifier.padding(14.dp).size(30.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(stringResource(R.string.remote_pair_required), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.remote_pair_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pairing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.remote_pairing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onPair, enabled = !pairing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Lucide.Link, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.remote_pair))
                }
            }
        }
    }
}
