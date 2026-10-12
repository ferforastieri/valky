package com.ferforastieri.valkyris.feature.devices

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.Volume1
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.ferforastieri.valkyris.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen remote shaped like a TV remote, laid out top to bottom: power
 * (centred) with the settings key beside it, the numeric keypad, the navigation
 * pad flanked by the volume and channel rockers — the volume rocker carries the
 * mute key between + and − — and home/back underneath. Keys repeat while held.
 */
@Composable
fun RemoteControlScreen(onBack: () -> Unit, vm: RemoteControlViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val remote = state.remote
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(14.dp))
        if (remote?.tokenConfigured == true) {
            PowerButton(powering = state.powering, onPower = vm::power, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Text(
            (remote?.name ?: stringResource(R.string.remote_control)) +
                " · " + if (remote?.tokenConfigured == true) stringResource(R.string.remote_paired) else stringResource(R.string.remote_not_paired),
            style = MaterialTheme.typography.labelSmall,
            color = if (remote?.tokenConfigured == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        state.error?.let { /* errors surface as a toast */ }
        when {
            remote == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !remote.tokenConfigured -> PairingPanel(pairing = state.pairing, onPair = vm::pair)
            else -> ConnectedRemote(state, vm)
        }
    }
}

@Composable
private fun PowerButton(powering: Boolean, onPower: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onPower,
        enabled = !powering,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (powering) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onErrorContainer)
            } else {
                Icon(Lucide.Power, stringResource(R.string.remote_power), Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ConnectedRemote(state: RemoteControlState, vm: RemoteControlViewModel) {
    val enabled = !state.powering
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NumberPad(enabled, vm::send)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Rocker(
                up = KeySpec(Lucide.Volume2, stringResource(R.string.remote_volume_up), "KEY_VOLUP"),
                down = KeySpec(Lucide.Volume1, stringResource(R.string.remote_volume_down), "KEY_VOLDOWN"),
                middle = KeySpec(Lucide.VolumeX, stringResource(R.string.remote_mute), "KEY_MUTE"),
                enabled = enabled, onSend = vm::send,
            )
            RemoteDPad(enabled, vm::send)
            Rocker(
                up = KeySpec(Lucide.ChevronUp, stringResource(R.string.remote_channel_up), "KEY_CHUP"),
                down = KeySpec(Lucide.ChevronDown, stringResource(R.string.remote_channel_down), "KEY_CHDOWN"),
                middle = KeySpec(Lucide.List, stringResource(R.string.remote_channel_list), "KEY_CH_LIST"),
                enabled = enabled, onSend = vm::send,
            )
        }
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoldKey(KeySpec(Lucide.ArrowLeft, stringResource(R.string.remote_back), "KEY_RETURN"), enabled, Modifier.size(58.dp), CircleShape, vm::send)
                HoldKey(KeySpec(Lucide.House, stringResource(R.string.remote_home), "KEY_HOME"), enabled, Modifier.size(62.dp), CircleShape, vm::send)
                HoldKey(KeySpec(Lucide.Settings, stringResource(R.string.settings), "KEY_MENU"), enabled, Modifier.size(58.dp), CircleShape, vm::send)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private data class KeySpec(val icon: ImageVector, val label: String, val key: String)

@Composable
private fun Rocker(up: KeySpec, down: KeySpec, enabled: Boolean, onSend: (String) -> Unit, middle: KeySpec? = null) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HoldKey(up, enabled, Modifier.size(54.dp), CircleShape, onSend)
            middle?.let { HoldKey(it, enabled, Modifier.size(54.dp), CircleShape, onSend) }
            HoldKey(down, enabled, Modifier.size(54.dp), CircleShape, onSend)
        }
    }
}

@Composable
private fun RemoteDPad(enabled: Boolean, onSend: (String) -> Unit) {
    Surface(
        Modifier.size(190.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Surface(
                onClick = { onSend("KEY_ENTER") },
                enabled = enabled,
                modifier = Modifier.size(72.dp).align(Alignment.Center),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.remote_ok), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                }
            }
            PadArrow(Lucide.ChevronUp, stringResource(R.string.remote_up), "KEY_UP", enabled, Modifier.align(Alignment.TopCenter), onSend)
            PadArrow(Lucide.ChevronLeft, stringResource(R.string.remote_left), "KEY_LEFT", enabled, Modifier.align(Alignment.CenterStart), onSend)
            PadArrow(Lucide.ChevronRight, stringResource(R.string.remote_right), "KEY_RIGHT", enabled, Modifier.align(Alignment.CenterEnd), onSend)
            PadArrow(Lucide.ChevronDown, stringResource(R.string.remote_down), "KEY_DOWN", enabled, Modifier.align(Alignment.BottomCenter), onSend)
        }
    }
}

@Composable
private fun PadArrow(icon: ImageVector, label: String, key: String, enabled: Boolean, modifier: Modifier, onSend: (String) -> Unit) {
    HoldKey(KeySpec(icon, label, key), enabled, modifier.size(48.dp), CircleShape, onSend)
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
                        HoldKey(KeySpec(Lucide.Tv, digit, "KEY_$digit"), enabled, Modifier.weight(1f).height(50.dp), RoundedCornerShape(15.dp), onSend, text = digit)
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldKey(spec: KeySpec, enabled: Boolean, modifier: Modifier, shape: Shape, onSend: (String) -> Unit, text: String? = null) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .9f else 1f, label = "key-scale")
    val color by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        label = "key-color",
    )
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.holdGesture(enabled, spec.key, onSend) { pressed = it },
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (pressed) 0.dp else 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (text != null) {
                Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            } else {
                Icon(spec.icon, spec.label, Modifier.size(22.dp), tint = if (pressed) MaterialTheme.colorScheme.secondary else LocalContentColor.current)
            }
        }
    }
}

/**
 * Sends the key on press and keeps repeating it while the finger stays down,
 * matching the way a physical remote ramps volume, channel and navigation.
 */
@Composable
private fun Modifier.holdGesture(enabled: Boolean, key: String, onSend: (String) -> Unit, onPressed: (Boolean) -> Unit): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    return pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                onPressed(true)
                onSend(key)
                repeatJob?.cancel()
                repeatJob = scope.launch {
                    delay(420)
                    while (true) {
                        onSend(key)
                        delay(140)
                    }
                }
                try {
                    tryAwaitRelease()
                } finally {
                    repeatJob?.cancel()
                    onPressed(false)
                }
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
