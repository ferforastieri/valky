package com.ferforastieri.valkyris.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.ferforastieri.valkyris.core.network.ApiNotice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ToastMessageHost(notices: Flow<ApiNotice>, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<ApiNotice?>(null) }
    LaunchedEffect(notices) {
        notices.collectLatest { notice ->
            current = notice
            delay(6_000)
            if (current == notice) current = null
        }
    }
    AnimatedVisibility(
        visible = current != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
    ) {
        current?.let { notice ->
            val accent = if (notice.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, accent.copy(alpha = .55f)),
                shadowElevation = 10.dp,
            ) {
                Row(Modifier.padding(start = 15.dp, top = 11.dp, bottom = 11.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (notice.success) Lucide.CircleCheck else Lucide.TriangleAlert,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(11.dp))
                    Text(
                        notice.message,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    IconButton({ current = null }) {
                        Icon(Lucide.X, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
