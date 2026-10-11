package com.ferforastieri.valkyris.feature.settings

import androidx.activity.compose.BackHandler
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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.TriangleAlert
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet

/**
 * Mandatory update gate. The sheet cannot be dismissed, swiped away or closed
 * with the back gesture: the app stays blocked until the signed update starts
 * installing. This restores the previous behaviour over the transient toast.
 */
@Composable
fun UpdateAvailableSheet(
    version: String,
    updating: Boolean,
    onUpdate: () -> Unit,
) {
    // The modal sheet already ignores taps outside; keep the system back button
    // from escaping the mandatory update as well.
    BackHandler(enabled = true) { /* Stay on the update sheet. */ }
    ValkyrisBottomSheet(
        title = stringResource(R.string.update_available),
        onDismiss = {},
        dismissEnabled = false,
        swipeToDismissEnabled = false,
        showCloseButton = false,
        actions = {
            Button(
                onClick = onUpdate,
                enabled = !updating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (updating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
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
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.update_required_body, version),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.TriangleAlert, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            stringResource(R.string.update_required_title),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.update_install_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
