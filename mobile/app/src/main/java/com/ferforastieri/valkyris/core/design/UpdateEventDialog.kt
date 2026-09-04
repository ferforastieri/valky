package com.ferforastieri.valkyris.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.UpdateInfo

@Composable
fun UpdateEventDialog(update: UpdateInfo, admin: Boolean, updating: Boolean, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val updatesServer = admin && update.serverUpdateAvailable
    val installedVersion = if (update.serverUpdateAvailable) update.currentVersion else update.clientVersion
    ValkyrisBottomSheet(
        title = stringResource(R.string.update_available),
        onDismiss = onDismiss,
        dismissEnabled = !updating,
        actions = {
            TextButton(onClick = onDismiss, enabled = !updating) { Text(stringResource(R.string.later)) }
            Button(
                onClick = onUpdate,
                enabled = !updating,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(if (updatesServer) Lucide.RefreshCw else Lucide.Download, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (updatesServer) R.string.update_now else R.string.download_apk))
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).background(MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.RefreshCw, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${installedVersion.ifBlank { "—" }}  →  ${update.latestVersion}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (updatesServer) R.string.update_event_admin_body else R.string.update_event_member_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
        }
    }
}
