package com.ferforastieri.valkyris.feature.rules

import androidx.compose.foundation.layout.*
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.DetectorKind
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.RuleSchedule
import com.ferforastieri.valkyris.core.model.MotionSettings
import com.ferforastieri.valkyris.core.model.MotionRegion
import com.ferforastieri.valkyris.core.model.RuleActions
import com.ferforastieri.valkyris.core.model.detectorLabelRes
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Video

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
                if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                else Icon(Lucide.Plus, contentDescription = stringResource(R.string.add_rule))
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
                Text(rule.name, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canEdit) IconButton(onClick = onEdit) { Icon(Lucide.Pencil, stringResource(R.string.edit_rule)) }
                    if (canEdit) IconButton(onClick = onDelete) { Icon(Lucide.Trash2, stringResource(R.string.remove_rule), tint = MaterialTheme.colorScheme.error) }
                }
                if (rule.schedule.start.isNotBlank()) Text("${rule.schedule.start}–${rule.schedule.end} · ${rule.schedule.timezone}", style = MaterialTheme.typography.bodySmall)
                rule.motion?.let { Text("Região selecionada · movimento por ${it.minDurationSeconds} s", style = MaterialTheme.typography.bodySmall) }
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
    var camera by remember(existing?.id) { mutableStateOf(cameras.firstOrNull { it.id == existing?.cameraId } ?: cameras.firstOrNull()) }
    var detector by remember(existing?.id) { mutableStateOf(detectors.firstOrNull { it.id == existing?.detectorTypes?.firstOrNull() } ?: detectors.firstOrNull()) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var recipients by remember(existing?.id) { mutableStateOf(existing?.actions?.recipientUserIds) }
    var record by remember(existing?.id) { mutableStateOf(existing?.actions?.record ?: true) }
    var notify by remember(existing?.id) { mutableStateOf(existing?.actions?.notify ?: true) }
    var alarm by remember(existing?.id) { mutableStateOf(existing?.actions?.alarm ?: false) }
    var scheduled by remember(existing?.id) { mutableStateOf(!existing?.schedule?.start.isNullOrBlank()) }
    var days by remember(existing?.id) { mutableStateOf(existing?.schedule?.days?.takeIf { it.isNotEmpty() } ?: (0..6).toList()) }
    var start by remember(existing?.id) { mutableStateOf(existing?.schedule?.start?.ifBlank { "22:00" } ?: "22:00") }
    var end by remember(existing?.id) { mutableStateOf(existing?.schedule?.end?.ifBlank { "06:00" } ?: "06:00") }
    var timezone by remember(existing?.id) { mutableStateOf(existing?.schedule?.timezone?.ifBlank { java.time.ZoneId.systemDefault().id } ?: java.time.ZoneId.systemDefault().id) }
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

    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(scrollContent = false,
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
                        actions = RuleActions(record, notify, alarm, recipients),
                        enabled = existing?.enabled ?: true,
                    ))
                },
                enabled = !saving && camera != null && detector != null && name.isNotBlank() && validSchedule && validMotion && cooldown.toIntOrNull() in 10..3600,
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.save))
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
            RuleSectionTitle("Quem recebe", "Estas pessoas receberão as notificações e os alarmes desta regra.")
            RuleActionRow(recipients == null, { recipients = if (it) null else emptyList() }, "Toda a família")
            if (recipients != null) {
                people.forEach { person ->
                    RuleActionRow(person.id in recipients.orEmpty(), { checked -> recipients = if (checked) recipients.orEmpty() + person.id else recipients.orEmpty() - person.id }, person.name)
                }
                if (recipients.orEmpty().isEmpty()) Text("Ninguém receberá notificações desta regra.", style = MaterialTheme.typography.bodySmall)
            }
            RuleSectionTitle("Quando monitorar", "Monitore o dia todo ou escolha dias e horários.")
            RuleActionRow(scheduled, { scheduled = it }, "Limitar por horário")
            if (scheduled) {
                Text("Dias de início do período", style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1,2,3,4,5,6,0).forEach { day ->
                        FilterChip(selected = day in days, onClick = { days = if (day in days) days - day else days + day }, label = { Text(listOf("Dom","Seg","Ter","Qua","Qui","Sex","Sáb")[day]) })
                    }
                }
                com.ferforastieri.valkyris.core.design.AdaptiveRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("Início · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text("Fim · HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(timezone, { timezone = it }, label = { Text("Fuso horário") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Ex.: segunda, 22:00–06:00 inclui a madrugada de terça.", style = MaterialTheme.typography.bodySmall)
                if (!validSchedule) Text("Selecione dias, horários distintos e um fuso válido (ex.: America/Sao_Paulo).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
        }
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
