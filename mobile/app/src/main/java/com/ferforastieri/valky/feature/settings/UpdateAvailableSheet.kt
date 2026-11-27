package com.ferforastieri.valky.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone
import com.ferforastieri.valky.R
import com.ferforastieri.valky.core.design.ValkyBottomSheet

/**
 * Aviso único de atualização: o mesmo texto no app e no painel. No Android o
 * botão pede ao updater do servidor e baixa o APK; no painel ele só reinicia o
 * servidor. O servidor é atualizado pelo instalador oficial, nunca pelo
 * container do backend.
 */
@Composable
fun UpdateAvailableSheet(
    version: String,
    serverUpdateAvailable: Boolean,
    apkUpdateAvailable: Boolean,
    admin: Boolean,
    updating: Boolean,
    message: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canUpdate = (admin && serverUpdateAvailable) || apkUpdateAvailable
    val body = when {
        serverUpdateAvailable && apkUpdateAvailable -> R.string.update_sheet_body_both
        serverUpdateAvailable -> R.string.update_sheet_body_server
        else -> R.string.update_sheet_body
    }
    val hint = when {
        serverUpdateAvailable && apkUpdateAvailable -> R.string.update_hint_both
        serverUpdateAvailable -> R.string.update_server_hint
        else -> R.string.update_install_hint
    }
    ValkyBottomSheet(
        title = stringResource(R.string.update_available),
        onDismiss = onDismiss,
        dismissEnabled = !updating,
        swipeToDismissEnabled = !updating,
        showCloseButton = true,
        actions = {
            Button(
                onClick = onUpdate,
                enabled = !updating && canUpdate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (updating) R.string.update_updating else R.string.update_now))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(
                        Lucide.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(body, version),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (serverUpdateAvailable && !admin) {
                Text(
                    stringResource(R.string.update_admin_required),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Download, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (updating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(message ?: stringResource(R.string.update_updating), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
