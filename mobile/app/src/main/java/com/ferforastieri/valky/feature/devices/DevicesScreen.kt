package com.ferforastieri.valky.feature.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Tv
import com.ferforastieri.valky.R
import com.ferforastieri.valky.feature.cameras.CamerasScreen
import com.ferforastieri.valky.feature.lights.LightsScreen
import kotlinx.coroutines.launch

/**
 * Devices groups every controllable appliance behind one section. Cameras stay
 * the default page; lighting and remote controls sit beside them. The selector
 * mirrors the bottom navigation dock and a horizontal swipe moves between the
 * pages.
 */
@Composable
fun DevicesScreen(
    admin: Boolean,
    onCamera: (String) -> Unit,
    onRemote: (String) -> Unit,
    onLight: (String) -> Unit,
    initialPage: Int = 0,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, 2)) { 3 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            DeviceKindSelector(
                selected = pagerState.currentPage,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
            )
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> CamerasScreen(onCamera = onCamera, canManage = admin)
                1 -> LightsScreen(admin = admin, onLight = onLight)
                else -> RemotesScreen(admin = admin, onRemote = onRemote)
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

// Same shape and rhythm as the bottom FloatingDock so both feel like one
// navigation system.
@Composable
private fun DeviceKindSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            deviceKinds.forEachIndexed { index, kind ->
                val active = selected == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            kind.icon,
                            contentDescription = stringResource(kind.label),
                            modifier = Modifier.size(20.dp),
                            tint = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
