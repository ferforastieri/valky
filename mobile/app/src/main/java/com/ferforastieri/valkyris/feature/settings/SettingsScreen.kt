package com.ferforastieri.valkyris.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ferforastieri.valkyris.MainViewModel
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.ferforastieri.valkyris.core.design.ColorTokens
import com.ferforastieri.valkyris.core.design.OperationalHeader
import org.unifiedpush.android.connector.UnifiedPush
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.BellOff
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudSync
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.UserPlus

@Composable
fun SettingsScreen(main: MainViewModel, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var pushStatusRes by remember { mutableIntStateOf(R.string.push_not_configured) }
    val theme by main.theme.collectAsStateWithLifecycle()
    val language by main.language.collectAsStateWithLifecycle()
    val admin by main.admin.collectAsStateWithLifecycle()
    val invitation by viewModel.invitation.collectAsStateWithLifecycle()
    var showInvitation by remember { mutableStateOf(false) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRefresh++
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionRefresh++
        manager.getNotificationChannel(AlarmNotifier.ALARMS)?.let { channel ->
            channel.setBypassDnd(manager.isNotificationPolicyAccessGranted)
            manager.createNotificationChannel(channel)
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    permissionRefresh

    val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val fullScreenAllowed = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
    val dndAllowed = manager.isNotificationPolicyAccessGranted

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val readyCount = listOf(notificationsAllowed, fullScreenAllowed, dndAllowed).count { it }
        OperationalHeader(
            icon = Lucide.Settings,
            eyebrow = stringResource(R.string.home_preferences),
            title = stringResource(R.string.settings),
            metric = "$readyCount/3",
            status = stringResource(R.string.ready_status),
        )
        Spacer(Modifier.height(6.dp))
        if (admin) {
            SettingsCard(
                Lucide.UserPlus,
                stringResource(R.string.invite_device),
                stringResource(R.string.invite_device_body),
            ) {
                showInvitation = true
            }
        }
        SettingsCard(
            Lucide.Bell,
            stringResource(R.string.notification_permission),
            stringResource(if (notificationsAllowed) R.string.permission_allowed else R.string.permission_required),
        ) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
        SettingsCard(Lucide.CloudSync, stringResource(R.string.notification_setup), stringResource(pushStatusRes)) {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
                if (success) {
                    UnifiedPush.register(context, "Valkyris home alerts", null)
                    pushStatusRes = R.string.push_waiting
                } else {
                    pushStatusRes = R.string.push_install_ntfy
                }
            }
        }
        SettingsCard(
            Lucide.AlarmClock,
            stringResource(R.string.full_screen_alarms),
            stringResource(if (fullScreenAllowed) R.string.permission_allowed else R.string.permission_required),
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:${context.packageName}")),
                )
            }
        }
        SettingsCard(
            Lucide.BellOff,
            stringResource(R.string.do_not_disturb_access),
            stringResource(if (dndAllowed) R.string.permission_allowed else R.string.permission_optional),
        ) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        SettingsCard(Lucide.Languages, stringResource(R.string.language), languageLabel(language)) {
            val next = when (language) {
                "system" -> "pt-BR"
                "pt-BR" -> "en"
                else -> "system"
            }
            main.setLanguage(next)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (next == "system") "" else next))
        }
        SettingsCard(Lucide.Moon, stringResource(R.string.theme), themeLabel(theme)) {
            main.setTheme(when (theme) { "system" -> "light"; "light" -> "dark"; else -> "system" })
        }
        SettingsInfoCard(Lucide.Database, stringResource(R.string.media_retention), stringResource(R.string.media_retention_value))
        Spacer(Modifier.height(8.dp))
        OutlinedButton({ main.signOut() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect_phone)) }
        Text(
            stringResource(R.string.local_only),
            Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showInvitation) {
        InvitationDialog(
            state = invitation,
            onCreate = viewModel::createInvitation,
            onDismiss = {
                showInvitation = false
                viewModel.clearInvitation()
            },
        )
    }
}

@Composable
private fun InvitationDialog(state: InvitationState, onCreate: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onCreate() }
    val bitmap = remember(state.uri) {
        state.uri?.let { runCatching { BarcodeEncoder().encodeBitmap(it, BarcodeFormat.QR_CODE, 720, 720) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.loading -> CircularProgressIndicator()
                    bitmap != null -> {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.invite_title),
                            modifier = Modifier.fillMaxWidth().background(Color.White, MaterialTheme.shapes.medium).padding(12.dp),
                        )
                        Text(stringResource(R.string.invite_expires), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onCreate, enabled = !state.loading) { Text(stringResource(R.string.generate_again)) } },
    )
}

@Composable
private fun themeLabel(value: String) = when (value) {
    "light" -> stringResource(R.string.light_theme)
    "dark" -> stringResource(R.string.dark_theme)
    else -> stringResource(R.string.system_default)
}

@Composable
private fun languageLabel(value: String) = when (value) {
    "pt-BR" -> "Português (Brasil)"
    "en" -> "English"
    else -> stringResource(R.string.system_default)
}

@Composable
private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = ColorTokens.BrandTile, shadowElevation = 3.dp) {
                Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Lucide.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsInfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = ColorTokens.BrandTile, shadowElevation = 3.dp) {
                Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
