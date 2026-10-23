package com.ferforastieri.valky.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Lucide

@Composable
fun ValkyTopBar(
    title: String,
    onNotifications: () -> Unit,
    notificationsSelected: Boolean = false,
    unreadNotifications: Int = 0,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sem a marca aqui: o símbolo vive na barra de baixo, no meio, e o
            // topo fica só com o título da tela.
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            actions?.invoke()
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onNotifications),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Bell,
                    contentDescription = "Notificações",
                    modifier = Modifier.size(22.dp),
                    tint = if (notificationsSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
                if (unreadNotifications > 0) {
                    val badge = if (unreadNotifications > 99) "99+" else unreadNotifications.toString()
                    Surface(
                        modifier = if (badge.length <= 1) {
                            Modifier.align(Alignment.TopEnd).size(18.dp)
                        } else {
                            Modifier.align(Alignment.TopEnd).defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) {
                        Box(
                            Modifier.padding(horizontal = if (badge.length <= 1) 0.dp else 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingDock(
    items: List<Pair<ImageVector, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    brandSelected: Boolean = false,
    onBrand: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 12.dp,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Metade dos destinos de cada lado, com a marca no meio: tocar nela
            // abre os dispositivos, a tela de uso mais frequente.
            val half = items.size / 2
            @Composable
            fun destination(index: Int) {
                val (icon, description) = items[index]
                val selected = index == selectedIndex
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = description,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            for (index in 0 until half) destination(index)
            if (onBrand != null) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (brandSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(onClick = onBrand),
                    contentAlignment = Alignment.Center,
                ) {
                    ValkyMark(Modifier.size(38.dp))
                }
            }
            for (index in half until items.size) destination(index)
        }
    }
}
