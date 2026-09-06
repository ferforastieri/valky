package com.ferforastieri.valkyris.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet

@Composable
fun StartupAlertPermissions() {
    val context = LocalContext.current
    val settings: SettingsViewModel = hiltViewModel()
    val pushConfiguration by settings.pushConfiguration.collectAsStateWithLifecycle()
    val manager = context.getSystemService(NotificationManager::class.java)
    var refresh by remember { mutableIntStateOf(0) }
    var dialogVisible by remember { mutableStateOf(false) }
    var startupHandled by remember { mutableStateOf(false) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
        dialogVisible = true
    }
    val firebaseAccountPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
                ?: error("Cannot read Firebase service account")
        }.onSuccess { settings.saveFirebaseServiceAccount(it) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    @Suppress("UNUSED_EXPRESSION")
    refresh

    val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val fullScreenAllowed = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
    val dndAllowed = manager.isNotificationPolicyAccessGranted
    val firebaseConfigured = pushConfiguration.configured
    val missingCount = listOf(notificationsAllowed, fullScreenAllowed, dndAllowed, firebaseConfigured).count { !it }

    LaunchedEffect(notificationsAllowed, firebaseConfigured, pushConfiguration.loading, startupHandled) {
        if (!startupHandled) {
            startupHandled = true
            if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!pushConfiguration.loading && missingCount > 0) {
                dialogVisible = true
            }
        } else if (!pushConfiguration.loading && missingCount > 0) {
            dialogVisible = true
        }
    }

    if (!dialogVisible || missingCount == 0) return

    val nextTitle = when {
        !notificationsAllowed -> stringResource(R.string.notification_permission)
        !fullScreenAllowed -> stringResource(R.string.full_screen_alarms)
        else -> stringResource(R.string.do_not_disturb_access)
    }
    ValkyrisBottomSheet(
        title = stringResource(R.string.alert_readiness_title),
        onDismiss = { dialogVisible = false },
        dismissEnabled = firebaseConfigured,
        actions = {
            Button(
                onClick = {
                    when {
                        !firebaseConfigured -> firebaseAccountPicker.launch(arrayOf("application/json", "text/json"))
                        Build.VERSION.SDK_INT >= 33 && !notificationsAllowed ->
                            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                        Build.VERSION.SDK_INT >= 34 && !fullScreenAllowed ->
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                    .setData(Uri.parse("package:${context.packageName}")),
                            )
                        !dndAllowed -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                },
                enabled = !pushConfiguration.saving,
            ) { Text(if (!firebaseConfigured) stringResource(R.string.upload_firebase_account) else stringResource(R.string.configure_permission, nextTitle)) }
            if (firebaseConfigured) TextButton({ dialogVisible = false }) { Text(stringResource(R.string.not_now)) }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Icon(
                    Lucide.BellRing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.alert_readiness_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionRow(stringResource(R.string.notification_permission), notificationsAllowed)
            PermissionRow(stringResource(R.string.full_screen_alarms), fullScreenAllowed)
            PermissionRow(stringResource(R.string.do_not_disturb_access), dndAllowed)
            PermissionRow(stringResource(R.string.firebase_service_account), firebaseConfigured)
        }
    }
}

@Composable
private fun PermissionRow(label: String, allowed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (allowed) Lucide.Check else Lucide.TriangleAlert,
            contentDescription = null,
            tint = if (allowed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            stringResource(if (allowed) R.string.permission_allowed else R.string.permission_required),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
