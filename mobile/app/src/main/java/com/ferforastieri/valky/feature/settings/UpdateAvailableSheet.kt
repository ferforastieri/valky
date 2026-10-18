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
import com.ferforastieri.valky.core.design.SkeletonList
import com.ferforastieri.valky.core.design.ValkySkeleton

/**
 * Update notice shown when a new signed APK is available. It is dismissible so
 * the app stays fully usable, but the caller re-opens it every time the app is
 * opened until the phone is updated.
 */
@Composable
fun UpdateAvailableSheet(
    version: String,
    updating: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ValkyBottomSheet(
        title = stringResource(R.string.update_available),
        onDismiss = onDismiss,
        dismissEnabled = !updating,
        swipeToDismissEnabled = !updating,
        showCloseButton = true,
        actions = {
            Button(
                onClick = onUpdate,
                enabled = !updating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (updating) R.string.update_downloading else R.string.update_now))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Icon(
                        Lucide.Smartphone,
                        contentDescription = null,
                        // Sobre o círculo primário o ícone precisa ser onPrimary:
                        // roxo sobre roxo sumia.
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.update_sheet_body, version),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
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
                        stringResource(R.string.update_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
