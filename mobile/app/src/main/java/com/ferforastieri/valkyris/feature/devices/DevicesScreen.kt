package com.ferforastieri.valkyris.feature.devices

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
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.feature.cameras.CamerasScreen
import com.ferforastieri.valkyris.feature.lights.LightsScreen
import kotlinx.coroutines.launch

/**
 * Devices groups every controllable appliance behind one section. Cameras stay
 * the default page; lighting and remote controls sit beside them. The selector
 * uses icons only and a horizontal swipe moves between the pages.
 */
@Composable
fun DevicesScreen(admin: Boolean, onCamera: (String) -> Unit, initialPage: Int = 0) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, 2)) { 3 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        DeviceKindSelector(
            selected = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
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
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            kind.icon,
                            contentDescription = stringResource(kind.label),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
