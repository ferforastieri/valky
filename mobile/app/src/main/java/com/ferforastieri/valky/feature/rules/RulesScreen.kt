package com.ferforastieri.valky.feature.rules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ferforastieri.valky.core.model.TrackedPerson
import com.ferforastieri.valky.R
import com.ferforastieri.valky.core.model.Camera
import com.ferforastieri.valky.core.model.DetectorKind
import com.ferforastieri.valky.core.model.Rule
import com.ferforastieri.valky.core.model.RuleSchedule
import com.ferforastieri.valky.core.model.MotionSettings
import com.ferforastieri.valky.core.model.MotionRegion
import com.ferforastieri.valky.core.model.RuleActions
import com.ferforastieri.valky.core.model.detectorLabelRes
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Video
import androidx.compose.foundation.shape.CircleShape
import com.ferforastieri.valky.core.design.SkeletonList
import com.ferforastieri.valky.core.design.ValkySkeleton

private val weekdayChips = listOf(
    1 to R.string.day_mon_short,
    2 to R.string.day_tue_short,
    3 to R.string.day_wed_short,
    4 to R.string.day_thu_short,
    5 to R.string.day_fri_short,
    6 to R.string.day_sat_short,
    0 to R.string.day_sun_short,
)

@Composable
fun CameraRulesSection(cameraId: String, vm: RulesViewModel = hiltViewModel()) {
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val detectors by vm.detectors.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    val cameraRules = rules.filter { it.cameraId == cameraId }
    val cameraName = cameras.firstOrNull { it.id == cameraId }?.name.orEmpty()

    if (!permissions.viewRules) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rules), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (cameraRules.isEmpty()) stringResource(R.string.no_rules) else "${cameraRules.size} ativa(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (permissions.editRules) TextButton(onClick = { creating = true }, enabled = detectors.isNotEmpty() && !saving) { Icon(Lucide.Plus, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_rule)) }
            }
            if (cameraRules.isEmpty()) Text("Configure alertas para esta câmera.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            cameraRules.forEach { RuleCard(it, canEdit = permissions.editRules, onEdit = { editing = it }, onDelete = { deleting = it }) }
        }
    }
    if (creating && permissions.editRules) RuleEditorDialog(cameras, detectors, fixedCameraID = cameraId, saving = saving, people = people, preview = vm::preview, onDismiss = { if (!saving) creating = false }) { vm.create(it) { success -> if (success) creating = false } }
    editing?.takeIf { permissions.editRules }?.let { existing -> RuleEditorDialog(cameras, detectors, existing, fixedCameraID = cameraId, saving = saving, people = people, preview = vm::preview, onDismiss = { if (!saving) editing = null }) { vm.update(existing.id, it) { success -> if (success) editing = null } } }
    deleting?.takeIf { permissions.editRules }?.let { rule -> DeleteRuleDialog(rule, saving, { deleting = null }) { vm.delete(rule.id) { if (it) deleting = null } } }
}

@Composable
fun RulesContent(
    rules: List<Rule>,
    canAdd: Boolean = true,
    saving: Boolean = false,
    onAdd: () -> Unit = {},
    onEdit: (Rule) -> Unit = {},
    onDelete: (Rule) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Lucide.SlidersHorizontal, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.no_rules), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(rules, key = { it.id }) { rule -> RuleCard(rule, onEdit = { onEdit(rule) }, onDelete = { onDelete(rule) }) }
                }
            }
        }
        if (canAdd) {
            FloatingActionButton(
                onClick = { if (!saving) onAdd() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp),
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(Lucide.Plus, contentDescription = stringResource(R.string.add_rule))
            }
        }
    }
}

@Composable
fun RuleCard(rule: Rule, onEdit: () -> Unit, onDelete: () -> Unit, canEdit: Boolean = true) {
    val critical = rule.detectorTypes.any { it in setOf("scream", "glass_break", "smoke_alarm", "fire_alarm", "siren", "tamper") }
    val motion = rule.detectorTypes.any { it == "motion" || it == "person" || it == "tamper" }
    val icon: ImageVector = when { critical -> Lucide.TriangleAlert; motion -> Lucide.Video; else -> Lucide.Mic }
    val accent: Color = if (critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = MaterialTheme.shapes.medium, color = accent.copy(alpha = .14f)) {
                Icon(icon, null, Modifier.padding(11.dp).size(23.dp), tint = accent)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (canEdit) IconButton(onClick = onEdit) { Icon(Lucide.Pencil, stringResource(R.string.edit_rule)) }
                    if (canEdit) IconButton(onClick = onDelete) { Icon(Lucide.Trash2, stringResource(R.string.remove_rule), tint = MaterialTheme.colorScheme.error) }
                }
                RuleScheduleSummary(rule)
                rule.motion?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Região selecionada · movimento por ${it.minDurationSeconds} s", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DeleteRuleDialog(rule: Rule, busy: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) = AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("Remover regra?") },
    text = { Text("A regra “${rule.name}” será removida permanentemente.") },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    confirmButton = { Button(onClick = onDelete, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(stringResource(R.string.remove_rule)) } },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorDialog(cameras: List<Camera>, detectors: List<DetectorKind>, existing: Rule? = null, fixedCameraID: String? = null, saving: Boolean, onDismiss: () -> Unit, preview: (suspend (String) -> ByteArray)? = null, people: List<TrackedPerson> = emptyList(), onSave: (Rule) -> Unit) {
    var alerts by remember(existing?.id) { mutableStateOf(existing?.actions?.alerts ?: com.ferforastieri.valky.core.model.AlertPresentation()) }
    var camera by remember(existing?.id) { mutableStateOf(cameras.firstOrNull { it.id == existing?.cameraId } ?: cameras.firstOrNull()) }
    var detector by remember(existing?.id) { mutableStateOf(detectors.firstOrNull { it.id == existing?.detectorTypes?.firstOrNull() } ?: detectors.firstOrNull()) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var record by remember(existing?.id) { mutableStateOf(existing?.actions?.record ?: true) }
    var notify by remember(existing?.id) { mutableStateOf(existing?.actions?.notify ?: true) }
    var alarm by remember(existing?.id) { mutableStateOf(existing?.actions?.alarm ?: false) }
    var scheduled by remember(existing?.id) { mutableStateOf(!existing?.schedule?.start.isNullOrBlank()) }
    var days by remember(existing?.id) { mutableStateOf(existing?.schedule?.days?.takeIf { it.isNotEmpty() } ?: (0..6).toList()) }
    var start by remember(existing?.id) { mutableStateOf(existing?.schedule?.start?.ifBlank { "22:00" } ?: "22:00") }
    var end by remember(existing?.id) { mutableStateOf(existing?.schedule?.end?.ifBlank { "06:00" } ?: "06:00") }
    var timezone by remember(existing?.id) { mutableStateOf(existing?.schedule?.timezone?.ifBlank { ZoneId.systemDefault().id } ?: ZoneId.systemDefault().id) }
    var pickingStart by remember(existing?.id) { mutableStateOf(false) }
    var pickingEnd by remember(existing?.id) { mutableStateOf(false) }
    var regionEnabled by remember(existing?.id) { mutableStateOf(existing?.motion != null) }
    var region by remember(existing?.id, fixedCameraID ?: camera?.id) { mutableStateOf(existing?.motion?.region?.takeIf { existing.cameraId == (fixedCameraID ?: camera?.id) }) }
    var duration by remember(existing?.id) { mutableStateOf((existing?.motion?.minDurationSeconds ?: 10).toString()) }
    var fraction by remember(existing?.id) { mutableStateOf((existing?.motion?.minChangedFraction ?: .05).toFloat()) }
    var cooldown by remember(existing?.id) { mutableStateOf((existing?.cooldownSeconds ?: 60).toString()) }
    val useRegion = detector?.id == "motion" && regionEnabled
    val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
    val validSchedule = !scheduled || (days.isNotEmpty() && timePattern.matches(start) && timePattern.matches(end) && start != end && runCatching { java.time.ZoneId.of(timezone) }.isSuccess)
    val validMotion = !useRegion || (region != null && duration.toIntOrNull() in 2..300)
    var cameraExpanded by remember { mutableStateOf(false) }
    var detectorExpanded by remember { mutableStateOf(false) }

    com.ferforastieri.valky.core.design.ValkyBottomSheet(scrollContent = false,
        title = stringResource(if (existing == null) R.string.add_rule else R.string.edit_rule),
        onDismiss = onDismiss,
        dismissEnabled = !saving,
        swipeToDismissEnabled = !useRegion,
        actions = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    onSave(Rule(
                        id = existing?.id.orEmpty(),
                        cameraId = fixedCameraID ?: checkNotNull(camera).id,
                        name = name,
                        detectorTypes = listOf(checkNotNull(detector).id),
                        confirmations = existing?.confirmations ?: 1,
                        schedule = if (scheduled) RuleSchedule(days.sorted(), start, end, timezone) else RuleSchedule(),
                        motion = if (useRegion) MotionSettings(checkNotNull(region), duration.toInt(), fraction.toDouble().coerceIn(.01, .5)) else null,
                        cooldownSeconds = cooldown.toInt(),
                        actions = RuleActions(record, notify, alarm, alerts),
                        enabled = existing?.enabled ?: true,
                    ))
                },
                enabled = !saving && camera != null && detector != null && name.isNotBlank() && validSchedule && validMotion && cooldown.toIntOrNull() in 10..3600,
            ) {
                Text(stringResource(R.string.save))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (existing == null) "Nova regra" else "Editando regra", style = MaterialTheme.typography.labelMedium)
                    Text(name.ifBlank { "Defina o que acompanhar" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(cameras.firstOrNull { it.id == (fixedCameraID ?: camera?.id) }?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            RuleSectionTitle("O que monitorar", "Escolha a câmera e o acontecimento que deve acionar esta regra.")
            if (fixedCameraID == null) ExposedDropdownMenuBox(cameraExpanded, { cameraExpanded = it }) {
                OutlinedTextField(camera?.name.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.cameras)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(cameraExpanded, { cameraExpanded = false }) { cameras.forEach { item -> DropdownMenuItem({ Text(item.name) }, { camera = item; cameraExpanded = false }) } }
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.rule_name)) }, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(detectorExpanded, { detectorExpanded = it }) {
                OutlinedTextField(detector?.let { stringResource(detectorLabelRes(it.id)) }.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.detector)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(detectorExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(detectorExpanded, { detectorExpanded = false }) { detectors.forEach { item -> DropdownMenuItem({ Text(stringResource(detectorLabelRes(item.id))) }, { detector = item; detectorExpanded = false }) } }
            }
            RuleSectionTitle("Quando monitorar", "Monitore o dia todo ou escolha dias e horários.")
            RuleActionRow(scheduled, { scheduled = it }, "Limitar por horário")
            if (scheduled) {
                if (days.isNotEmpty() && timePattern.matches(start) && timePattern.matches(end)) {
                    ScheduleSummary(RuleSchedule(days.sorted(), start, end, timezone))
                }
                Text(stringResource(R.string.schedule_days_start_hint), style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    weekdayChips.forEach { (day, label) ->
                        FilterChip(
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                com.ferforastieri.valky.core.design.AdaptiveRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleTimeButton(stringResource(R.string.schedule_start), start, { pickingStart = true }, Modifier.weight(1f))
                    ScheduleTimeButton(stringResource(R.string.schedule_end), end, { pickingEnd = true }, Modifier.weight(1f))
                }
                TimezoneField(timezone) { timezone = it }
                if (!validSchedule) Text(stringResource(R.string.schedule_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (detector?.id == "motion") {
                RuleSectionTitle("Onde detectar movimento", "Delimite a parte da imagem que deseja acompanhar.")
                RuleActionRow(regionEnabled, { regionEnabled = it }, "Movimento persistente em uma região")
                if (regionEnabled) {
                    MotionRegionEditor(fixedCameraID ?: camera?.id.orEmpty(), region, { region = it }, preview)
                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, label = { Text("Tempo mínimo de movimento (segundos)") }, supportingText = { Text("De 2 a 300 s; pausas e perda de imagem reiniciam a contagem.") }, isError = duration.toIntOrNull() !in 2..300, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Mudança mínima na região: ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(value = fraction, onValueChange = { fraction = it }, valueRange = .01f.. .5f)
                    Text("Valores menores detectam movimentos mais sutis. O alerta indica movimento na região, não identifica o bebê nem avalia risco, respiração ou postura.", style = MaterialTheme.typography.bodySmall)
                }
            }
            RuleSectionTitle("O que acontece", "Defina a gravação, a notificação e o alarme sonoro.")
            OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit) }, label = { Text("Intervalo entre alertas (segundos)") }, supportingText = { Text("De 10 a 3.600 s") }, isError = cooldown.toIntOrNull() !in 10..3600, singleLine = true, modifier = Modifier.fillMaxWidth())
            RuleActionRow(record, { record = it }, stringResource(R.string.record_media))
            RuleActionRow(notify, { notify = it }, stringResource(R.string.send_notification))
            RuleActionRow(alarm, { alarm = it }, stringResource(R.string.sound_alarm))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.rule_alerts), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rule_alerts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(alerts.notificationTitle, { alerts = alerts.copy(notificationTitle = it.take(80)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.alert_notification_title)) })
            OutlinedTextField(alerts.notificationBody, { alerts = alerts.copy(notificationBody = it.take(240)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.alert_notification_body)) })
            OutlinedTextField(alerts.alarmTitle, { alerts = alerts.copy(alarmTitle = it.take(80)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.alert_alarm_title)) })
            OutlinedTextField(alerts.alarmBody, { alerts = alerts.copy(alarmBody = it.take(240)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.alert_alarm_body)) })
            Text(stringResource(R.string.alert_alarm_sound), style = MaterialTheme.typography.labelLarge)
            listOf("alarm" to R.string.alert_sound_alarm, "ringtone" to R.string.alert_sound_ringtone, "silent" to R.string.alert_sound_silent).forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { alerts = alerts.copy(alarmSound = value) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = alerts.alarmSound == value, onClick = { alerts = alerts.copy(alarmSound = value) })
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alert_vibrate), Modifier.weight(1f))
                Switch(alerts.vibrate, { alerts = alerts.copy(vibrate = it) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alert_full_screen), Modifier.weight(1f))
                Switch(alerts.fullScreen, { alerts = alerts.copy(fullScreen = it) })
            }
            Text(stringResource(R.string.alert_preview), style = MaterialTheme.typography.labelLarge)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(alerts.notificationTitle.ifBlank { stringResource(R.string.notification_channel_events) }, style = MaterialTheme.typography.titleMedium)
                    Text(alerts.notificationBody.ifBlank { stringResource(R.string.notification_event_body) })
                    HorizontalDivider()
                    Text(alerts.alarmTitle.ifBlank { stringResource(R.string.alarm_title) }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(alerts.alarmBody.ifBlank { stringResource(R.string.notification_alarm_body) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

        }
    }
    if (pickingStart) {
        ScheduleTimePickerDialog(start, onDismiss = { pickingStart = false }) { start = it; pickingStart = false }
    }
    if (pickingEnd) {
        ScheduleTimePickerDialog(end, onDismiss = { pickingEnd = false }) { end = it; pickingEnd = false }
    }
}

@Composable
private fun RuleActionRow(checked: Boolean, onChecked: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChecked)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun RuleSectionTitle(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RuleScheduleSummary(rule: Rule) {
    val scheduled = rule.schedule.start.isNotBlank()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.AlarmClock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (scheduled) scheduleTitle(rule.schedule) else stringResource(R.string.schedule_always),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (scheduled) {
                    val behavior = if (rule.actions.alarm) stringResource(R.string.schedule_alarm_only_short) else ""
                    val detail = listOf(scheduleTimezoneLabel(rule.schedule.timezone), behavior)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ScheduleSummary(schedule: RuleSchedule) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.AlarmClock, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(scheduleTitle(schedule), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.schedule_behavior_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun scheduleTitle(schedule: RuleSchedule): String {
    val days = scheduleDaysLabel(schedule.days)
    val time = when {
        schedule.start.isBlank() || schedule.end.isBlank() -> stringResource(R.string.schedule_all_day)
        schedule.start > schedule.end -> stringResource(R.string.schedule_overnight, schedule.start, schedule.end)
        else -> stringResource(R.string.schedule_time_range, schedule.start, schedule.end)
    }
    return "$days · $time"
}

@Composable
private fun scheduleDaysLabel(days: List<Int>): String {
    val selected = days.toSet()
    if (selected.isEmpty() || selected.size >= 7) return stringResource(R.string.schedule_every_day)
    val labels = mapOf(
        0 to stringResource(R.string.day_sun_short),
        1 to stringResource(R.string.day_mon_short),
        2 to stringResource(R.string.day_tue_short),
        3 to stringResource(R.string.day_wed_short),
        4 to stringResource(R.string.day_thu_short),
        5 to stringResource(R.string.day_fri_short),
        6 to stringResource(R.string.day_sat_short),
    )
    val runs = mutableListOf<MutableList<Int>>()
    var previousIndex = -2
    listOf(1, 2, 3, 4, 5, 6, 0).forEachIndexed { index, day ->
        if (day in selected) {
            if (index == previousIndex + 1) runs.last().add(day) else runs.add(mutableListOf(day))
            previousIndex = index
        }
    }
    if (runs.isEmpty()) return stringResource(R.string.schedule_every_day)
    return runs.joinToString(", ") { run ->
        when (run.size) {
            1 -> labels.getValue(run.first())
            2 -> "${labels.getValue(run[0])}, ${labels.getValue(run[1])}"
            else -> "${labels.getValue(run.first())}–${labels.getValue(run.last())}"
        }
    }
}

@Composable
private fun scheduleTimezoneLabel(timezone: String): String {
    val zone = timezone.ifBlank { ZoneId.systemDefault().id }
    val name = if (zone == ZoneId.systemDefault().id) {
        stringResource(R.string.schedule_local_timezone)
    } else {
        zone.substringAfterLast('/').replace('_', ' ')
    }
    return stringResource(R.string.schedule_timezone_value, name)
}

@Composable
private fun ScheduleTimeButton(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Icon(Lucide.AlarmClock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimezoneField(value: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val common = remember {
        listOf(
            "America/Sao_Paulo",
            "America/Manaus",
            "America/Belem",
            "America/Fortaleza",
            "America/Recife",
            "America/Cuiaba",
            "America/Porto_Velho",
            "America/Rio_Branco",
            "America/Noronha",
            "UTC",
        )
    }
    val options = (listOf(value) + common)
        .filter { it.isNotBlank() && (value.isBlank() || it.contains(value, ignoreCase = true)) }
        .distinct()
    Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it); expanded = true },
                label = { Text(stringResource(R.string.schedule_timezone)) },
                supportingText = { Text(stringResource(R.string.schedule_timezone_hint)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                singleLine = true,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded, { expanded = false }) {
                options.forEach { zone ->
                    DropdownMenuItem(
                        text = { Text(zone) },
                        onClick = { onValueChange(zone); expanded = false },
                    )
                }
            }
        }
        TextButton(onClick = { onValueChange(ZoneId.systemDefault().id) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.schedule_use_device_timezone))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val (hour, minute) = remember(initial) { parseClock(initial) }
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_choose_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm("${state.hour.toString().padStart(2, '0')}:${state.minute.toString().padStart(2, '0')}")
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun parseClock(value: String): Pair<Int, Int> {
    val parts = value.split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hour to minute
}
