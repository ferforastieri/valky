package com.ferforastieri.valkyris.feature.settings

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
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet

@Composable
fun UpdateAvailableSheet(
    version: String,
    updating: Boolean,
    onUpdate: () -> Unit,
) {
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
                Text(stringResource(R.string.update_now))
            }
        },
    ) {
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
                stringResource(R.string.update_sheet_body, version),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
