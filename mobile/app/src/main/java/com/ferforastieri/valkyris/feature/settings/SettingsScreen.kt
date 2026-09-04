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
import androidx.compose.ui.res.pluralStringResource
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
import com.ferforastieri.valkyris.BuildConfig
import com.ferforastieri.valkyris.core.alarm.AlarmNotifier
import com.ferforastieri.valkyris.core.design.ColorTokens
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.RetentionSettings
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
import com.composables.icons.lucide.ShieldCheck
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
    val retention by viewModel.retention.collectAsStateWithLifecycle()
    var showInvitation by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showRetention by remember { mutableStateOf(false) }
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

    SettingsContent(
        admin = admin,
        notificationsAllowed = notificationsAllowed,
        fullScreenAllowed = fullScreenAllowed,
        dndAllowed = dndAllowed,
        language = language,
        theme = theme,
        retention = retention.value,
        onInvite = { showInvitation = true },
        onPermissions = { showPermissions = true },
        onLanguage = { showLanguage = true },
        onTheme = { main.setTheme(when (theme) { "system" -> "light"; "light" -> "dark"; else -> "system" }) },
        onRetention = { showRetention = true },
        onSignOut = main::signOut,
    )
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
    if (showLanguage) {
        LanguageSheet(
            current = language,
            onSelect = { selected ->
                main.setLanguage(selected)
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(if (selected == "system") "" else selected),
                )
                showLanguage = false
            },
            onDismiss = { showLanguage = false },
        )
    }
    if (showPermissions) {
        PermissionsSheet(
            notificationsAllowed = notificationsAllowed,
            fullScreenAllowed = fullScreenAllowed,
            dndAllowed = dndAllowed,
            pushStatusRes = pushStatusRes,
            onNotifications = {
                if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
            onPush = {
                UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
                    if (success) {
                        UnifiedPush.register(context, "Valkyris home alerts", null)
                        pushStatusRes = R.string.push_waiting
                    } else pushStatusRes = R.string.push_install_ntfy
                }
            },
            onFullScreen = {
                if (Build.VERSION.SDK_INT >= 34) context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(android.net.Uri.parse("package:${context.packageName}")),
                )
            },
            onDnd = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            onDismiss = { showPermissions = false },
        )
    }
    if (showRetention) {
        RetentionSheet(
            current = retention.value,
            saving = retention.saving,
            onSave = { value -> viewModel.saveRetention(value) { showRetention = false } },
            onDismiss = { if (!retention.saving) showRetention = false },
        )
    }
}

@Composable
fun SettingsContent(
    admin: Boolean,
    notificationsAllowed: Boolean,
    fullScreenAllowed: Boolean,
    dndAllowed: Boolean,
    language: String,
    theme: String,
    retention: RetentionSettings = RetentionSettings(),
    version: String = BuildConfig.VERSION_NAME,
    onInvite: () -> Unit = {},
    onPermissions: () -> Unit = {},
    onLanguage: () -> Unit = {},
    onTheme: () -> Unit = {},
    onRetention: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val readyCount = listOf(notificationsAllowed, fullScreenAllowed, dndAllowed).count { it }
        if (admin) {
            SettingsCard(
                Lucide.UserPlus,
                stringResource(R.string.invite_device),
                stringResource(R.string.invite_device_body),
            ) { onInvite() }
        }
        SettingsCard(
            Lucide.ShieldCheck,
            stringResource(R.string.permissions),
            stringResource(R.string.permissions_summary, readyCount),
        ) { onPermissions() }
        SettingsCard(Lucide.Languages, stringResource(R.string.language), languageLabel(language), onClick = onLanguage)
        SettingsCard(Lucide.Moon, stringResource(R.string.theme), themeLabel(theme), onClick = onTheme)
        if (admin) {
            SettingsCard(
                Lucide.Database,
                stringResource(R.string.media_retention),
                stringResource(R.string.retention_summary, retention.maxAgeDays, retention.maxStorageGB),
                onClick = onRetention,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onSignOut, Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect_phone)) }
        Column(
            Modifier.align(Alignment.CenterHorizontally),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.app_version, version),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.copyright_owner),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetentionSheet(
    current: RetentionSettings,
    saving: Boolean = false,
    onSave: (RetentionSettings) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var days by remember(current) { mutableIntStateOf(current.maxAgeDays) }
    var storage by remember(current) { mutableLongStateOf(current.maxStorageGB) }
    ValkyrisBottomSheet(
        title = stringResource(R.string.media_retention),
        onDismiss = onDismiss,
        dismissEnabled = !saving,
        actions = {
            TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onSave(RetentionSettings(days, storage)) }, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.save))
            }
        },
    ) {
        Text(
            stringResource(R.string.retention_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.retention_days), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(1, 3, 7, 14, 30).forEach { option ->
                FilterChip(
                    selected = days == option,
                    onClick = { days = option },
                    label = { Text(pluralStringResource(R.plurals.days_value, option, option)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.retention_storage), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(1L, 2L, 5L, 10L, 20L).forEach { option ->
                FilterChip(
                    selected = storage == option,
                    onClick = { storage = option },
                    label = { Text("$option GB") },
                )
            }
        }
    }
}

@Composable
fun PermissionsSheet(
    notificationsAllowed: Boolean,
    fullScreenAllowed: Boolean,
    dndAllowed: Boolean,
    pushStatusRes: Int,
    onNotifications: () -> Unit = {},
    onPush: () -> Unit = {},
    onFullScreen: () -> Unit = {},
    onDnd: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    ValkyrisBottomSheet(
        title = stringResource(R.string.permissions),
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(R.string.alert_readiness_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionOption(
                icon = Lucide.Bell,
                title = stringResource(R.string.notification_permission),
                status = stringResource(if (notificationsAllowed) R.string.permission_allowed else R.string.permission_required),
                allowed = notificationsAllowed,
                onClick = onNotifications,
            )
            PermissionOption(
                icon = Lucide.CloudSync,
                title = stringResource(R.string.notification_setup),
                status = stringResource(pushStatusRes),
                allowed = pushStatusRes == R.string.push_waiting,
                onClick = onPush,
            )
            PermissionOption(
                icon = Lucide.AlarmClock,
                title = stringResource(R.string.full_screen_alarms),
                status = stringResource(if (fullScreenAllowed) R.string.permission_allowed else R.string.permission_required),
                allowed = fullScreenAllowed,
                onClick = onFullScreen,
            )
            PermissionOption(
                icon = Lucide.BellOff,
                title = stringResource(R.string.do_not_disturb_access),
                status = stringResource(if (dndAllowed) R.string.permission_allowed else R.string.permission_optional),
                allowed = dndAllowed,
                onClick = onDnd,
            )
        }
    }
}

@Composable
private fun PermissionOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    allowed: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (allowed) MaterialTheme.colorScheme.secondary.copy(alpha = .14f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = ColorTokens.BrandTile) {
                Icon(icon, null, Modifier.padding(9.dp).size(21.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Lucide.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InvitationDialog(state: InvitationState, onCreate: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onCreate() }
    val bitmap = remember(state.uri) {
        state.uri?.let { runCatching { BarcodeEncoder().encodeBitmap(it, BarcodeFormat.QR_CODE, 720, 720) }.getOrNull() }
    }
    ValkyrisBottomSheet(
        title = stringResource(R.string.invite_title),
        onDismiss = onDismiss,
        actions = {
            TextButton(onCreate, enabled = !state.loading) { Text(stringResource(R.string.generate_again)) }
            Button(onDismiss) { Text(stringResource(R.string.close)) }
        },
    ) {
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
    }
}

@Composable
fun LanguageSheet(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ValkyrisBottomSheet(
        title = stringResource(R.string.choose_language),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageOption("system", stringResource(R.string.system_default), current, onSelect)
            LanguageOption("pt-BR", stringResource(R.string.portuguese_brazil), current, onSelect)
            LanguageOption("en", stringResource(R.string.english), current, onSelect)
        }
    }
}

@Composable
private fun LanguageOption(value: String, label: String, current: String, onSelect: (String) -> Unit) {
    Surface(
        onClick = { onSelect(value) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (current == value) MaterialTheme.colorScheme.secondary.copy(alpha = .16f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = current == value, onClick = { onSelect(value) })
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = if (current == value) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
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
