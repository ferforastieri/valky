package com.ferforastieri.valkyris.feature.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Tv
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.feature.cameras.CamerasScreen
import com.ferforastieri.valkyris.feature.lights.LightsScreen

/**
 * Devices groups every controllable appliance behind one section. Cameras stay
 * the default page; lighting and remote controls sit beside them.
 */
@Composable
fun DevicesScreen(admin: Boolean, onCamera: (String) -> Unit, initialPage: Int = 0) {
    var page by remember { mutableIntStateOf(initialPage.coerceIn(0, 2)) }
    Column(Modifier.fillMaxSize()) {
        DeviceKindSelector(page, { page = it }, Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        Box(Modifier.weight(1f)) {
            when (page) {
                0 -> CamerasScreen(onCamera = onCamera, canManage = admin)
                1 -> LightsScreen(admin = admin)
                else -> RemotesScreen(admin = admin)
            }
        }
    }
}

private data class DeviceKind(val icon: ImageVector, val label: Int)

private val deviceKinds = listOf(
    DeviceKind(Lucide.Camera, R.string.cameras),
    DeviceKind(Lucide.Lightbulb, R.string.lighting),
    DeviceKind(Lucide.Tv, R.string.remote_control),
)

@Composable
private fun DeviceKindSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            deviceKinds.forEachIndexed { index, kind ->
                val active = selected == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp),
                    color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
                    contentColor = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(kind.icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(kind.label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
