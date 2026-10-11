package com.ferforastieri.valkyris.feature.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.Volume1
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.RemoteApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen remote laid out like a TV remote: the installed apps sit on top
 * and the main controls — D-pad, volume, channel, home/back/menu and mute —
 * stay within thumb reach at the bottom, without scrolling. Keys repeat while
 * held, and the numeric keypad is a toggle so it never pushes the controls off
 * screen. The system back gesture leaves the screen.
 */
@Composable
fun RemoteControlScreen(onBack: () -> Unit, vm: RemoteControlViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val remote = state.remote
    var keypad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                KeypadToggle(active = keypad, onToggle = { keypad = !keypad })
                Spacer(Modifier.width(8.dp))
                PowerButton(powering = state.powering, onPower = vm::power)
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        when {
            remote == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !remote.tokenConfigured -> PairingPanel(pairing = state.pairing, onPair = vm::pair)
            else -> ConnectedRemote(state, vm, keypad)
        }
    }
}

@Composable
private fun KeypadToggle(active: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Text("123", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun PowerButton(powering: Boolean, onPower: () -> Unit) {
    Surface(
        onClick = onPower,
        enabled = !powering,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .35f)),
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            if (powering) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onErrorContainer)
            } else {
                Icon(Lucide.Power, stringResource(R.string.remote_power), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ConnectedRemote(state: RemoteControlState, vm: RemoteControlViewModel, keypad: Boolean) {
    val enabled = !state.powering
    Column(Modifier.fillMaxSize()) {
        AppsRow(state, vm)
        Spacer(Modifier.weight(1f))
        NavigationPad(enabled, vm::send)
        Spacer(Modifier.height(14.dp))
        BottomRow(enabled, vm::send)
        if (keypad) {
            Spacer(Modifier.height(12.dp))
            NumberPad(enabled, vm::send)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AppsRow(state: RemoteControlState, vm: RemoteControlViewModel) {
    when {
        state.appsLoading && state.apps.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.apps.isEmpty() -> TextButton(onClick = { vm.loadApps() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.remote_apps_load))
        }
        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.apps, key = { it.id }) { app -> AppChip(app) { vm.launch(app.id) } }
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

private data class KeySpec(val icon: ImageVector, val label: String, val key: String)

@Composable
private fun NavigationPad(enabled: Boolean, onSend: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Rocker(
            top = KeySpec(Lucide.Volume2, stringResource(R.string.remote_volume_up), "KEY_VOLUP"),
            bottom = KeySpec(Lucide.Volume1, stringResource(R.string.remote_volume_down), "KEY_VOLDOWN"),
            enabled = enabled, onSend = onSend,
        )
        DPad(enabled, onSend)
        Rocker(
            top = KeySpec(Lucide.ChevronUp, stringResource(R.string.remote_channel_up), "KEY_CHUP"),
            bottom = KeySpec(Lucide.ChevronDown, stringResource(R.string.remote_channel_down), "KEY_CHDOWN"),
            enabled = enabled, onSend = onSend,
        )
    }
}

@Composable
private fun Rocker(top: KeySpec, bottom: KeySpec, enabled: Boolean, onSend: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton(enabled, Modifier.size(54.dp), CircleShape, onSend, top)
            KeyButton(enabled, Modifier.size(54.dp), CircleShape, onSend, bottom)
        }
    }
}

@Composable
private fun DPad(enabled: Boolean, onSend: (String) -> Unit) {
    Surface(
        Modifier.size(196.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            KeyButton(enabled, Modifier.align(Alignment.TopCenter).size(52.dp), CircleShape, onSend, KeySpec(Lucide.ChevronUp, stringResource(R.string.remote_up), "KEY_UP"))
            KeyButton(enabled, Modifier.align(Alignment.BottomCenter).size(52.dp), CircleShape, onSend, KeySpec(Lucide.ChevronDown, stringResource(R.string.remote_down), "KEY_DOWN"))
            KeyButton(enabled, Modifier.align(Alignment.CenterStart).size(52.dp), CircleShape, onSend, KeySpec(Lucide.ChevronLeft, stringResource(R.string.remote_left), "KEY_LEFT"))
            KeyButton(enabled, Modifier.align(Alignment.CenterEnd).size(52.dp), CircleShape, onSend, KeySpec(Lucide.ChevronRight, stringResource(R.string.remote_right), "KEY_RIGHT"))
            Surface(
                onClick = { onSend("KEY_ENTER") },
                enabled = enabled,
                modifier = Modifier.align(Alignment.Center).size(84.dp),
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
private fun NumberPad(enabled: Boolean, onSend: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    if (digit.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        KeyButton(
                            enabled,
                            Modifier.weight(1f).height(52.dp),
                            RoundedCornerShape(16.dp),
                            onSend,
                            KeySpec(Lucide.Tv, digit, "KEY_$digit"),
                            text = digit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomRow(enabled: Boolean, onSend: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyButton(enabled, Modifier.weight(1f).height(58.dp), RoundedCornerShape(16.dp), onSend, KeySpec(Lucide.House, stringResource(R.string.remote_home), "KEY_HOME"))
        KeyButton(enabled, Modifier.weight(1f).height(58.dp), RoundedCornerShape(16.dp), onSend, KeySpec(Lucide.ArrowLeft, stringResource(R.string.remote_back), "KEY_RETURN"))
        KeyButton(enabled, Modifier.weight(1f).height(58.dp), RoundedCornerShape(16.dp), onSend, KeySpec(Lucide.Menu, stringResource(R.string.remote_menu), "KEY_MENU"))
        KeyButton(enabled, Modifier.weight(1f).height(58.dp), RoundedCornerShape(16.dp), onSend, KeySpec(Lucide.VolumeX, stringResource(R.string.remote_mute), "KEY_MUTE"))
    }
}

@Composable
private fun KeyButton(
    enabled: Boolean,
    modifier: Modifier,
    shape: Shape,
    onSend: (String) -> Unit,
    spec: KeySpec,
    text: String? = null,
) {
    Surface(
        modifier = modifier.then(holdModifier(enabled) { onSend(spec.key) }),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (text != null) {
                Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            } else {
                Icon(spec.icon, spec.label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/**
 * Sends the key on press and keeps repeating it while the finger stays down,
 * matching the way a physical remote ramps volume, channel and navigation.
 */
@Composable
private fun holdModifier(enabled: Boolean, onKey: () -> Unit): Modifier {
    if (!enabled) return Modifier
    val scope = rememberCoroutineScope()
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    return Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                onKey()
                repeatJob?.cancel()
                repeatJob = scope.launch {
                    delay(420)
                    while (true) {
                        onKey()
                        delay(140)
                    }
                }
                tryAwaitRelease()
                repeatJob?.cancel()
            },
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
