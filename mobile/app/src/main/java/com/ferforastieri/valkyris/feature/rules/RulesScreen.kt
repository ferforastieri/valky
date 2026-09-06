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
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.DetectorKind
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.RuleActions
import com.ferforastieri.valkyris.core.model.detectorLabelRes
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Video

@Composable
fun RulesScreen(vm: RulesViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val detectors by vm.detectors.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var deleting by remember { mutableStateOf<Rule?>(null) }

    RulesContent(rules, cameras.isNotEmpty() && detectors.isNotEmpty(), saving, onAdd = { creating = true }, onEdit = { editing = it }, onDelete = { deleting = it })
    if (creating) {
        RuleEditorDialog(cameras, detectors, saving = saving, onDismiss = { if (!saving) creating = false }) {
            vm.create(it) { success -> if (success) creating = false }
        }
    }
    editing?.let { existing ->
        RuleEditorDialog(cameras, detectors, existing, saving = saving, onDismiss = { if (!saving) editing = null }) {
            vm.update(existing.id, it) { success -> if (success) editing = null }
        }
    }
    deleting?.let { rule -> DeleteRuleDialog(rule, saving, { deleting = null }) { vm.delete(rule.id) { if (it) deleting = null } } }
}

@Composable
fun CameraRulesSection(cameraId: String, vm: RulesViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val detectors by vm.detectors.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    val cameraRules = rules.filter { it.cameraId == cameraId }
    val cameraName = cameras.firstOrNull { it.id == cameraId }?.name.orEmpty()

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
                TextButton(onClick = { creating = true }, enabled = detectors.isNotEmpty() && !saving) { Icon(Lucide.Plus, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_rule)) }
            }
            if (cameraRules.isEmpty()) Text("Configure alertas para esta câmera.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            cameraRules.forEach { RuleCard(it, onEdit = { editing = it }, onDelete = { deleting = it }) }
        }
    }
    if (creating) RuleEditorDialog(cameras, detectors, fixedCameraID = cameraId, saving = saving, onDismiss = { if (!saving) creating = false }) { vm.create(it) { success -> if (success) creating = false } }
    editing?.let { existing -> RuleEditorDialog(cameras, detectors, existing, fixedCameraID = cameraId, saving = saving, onDismiss = { if (!saving) editing = null }) { vm.update(existing.id, it) { success -> if (success) editing = null } } }
    deleting?.let { rule -> DeleteRuleDialog(rule, saving, { deleting = null }) { vm.delete(rule.id) { if (it) deleting = null } } }
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
fun RuleCard(rule: Rule, onEdit: () -> Unit, onDelete: () -> Unit) {
    val critical = rule.detectorTypes.any { it in setOf("scream", "glass_break", "smoke_alarm", "fire_alarm", "siren", "tamper") }
    val motion = rule.detectorTypes.any { it == "motion" || it == "person" || it == "tamper" }
    val icon: ImageVector = when { critical -> Lucide.TriangleAlert; motion -> Lucide.Video; else -> Lucide.Mic }
    val accent: Color = if (critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    val description = stringResource(when { critical -> R.string.rule_critical_description; motion -> R.string.rule_motion_description; else -> R.string.rule_audio_description })
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onEdit) { Icon(Lucide.SlidersHorizontal, stringResource(R.string.edit_rule)) }
                    IconButton(onClick = onDelete) { Icon(Lucide.Trash2, stringResource(R.string.remove_rule), tint = MaterialTheme.colorScheme.error) }
                }
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorDialog(cameras: List<Camera>, detectors: List<DetectorKind>, existing: Rule? = null, fixedCameraID: String? = null, saving: Boolean, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var camera by remember(existing?.id) { mutableStateOf(cameras.firstOrNull { it.id == existing?.cameraId } ?: cameras.firstOrNull()) }
    var detector by remember(existing?.id) { mutableStateOf(detectors.firstOrNull { it.id == existing?.detectorTypes?.firstOrNull() } ?: detectors.firstOrNull()) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var record by remember(existing?.id) { mutableStateOf(existing?.actions?.record ?: true) }
    var notify by remember(existing?.id) { mutableStateOf(existing?.actions?.notify ?: true) }
    var alarm by remember(existing?.id) { mutableStateOf(existing?.actions?.alarm ?: false) }
    var cameraExpanded by remember { mutableStateOf(false) }
    var detectorExpanded by remember { mutableStateOf(false) }

    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title = stringResource(if (existing == null) R.string.add_rule else R.string.edit_rule),
        onDismiss = onDismiss,
        dismissEnabled = !saving,
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
                        actions = RuleActions(record, notify, alarm),
                        enabled = existing?.enabled ?: true,
                    ))
                },
                enabled = !saving && camera != null && detector != null && name.isNotBlank(),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.save))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fixedCameraID == null) ExposedDropdownMenuBox(cameraExpanded, { cameraExpanded = it }) {
                OutlinedTextField(camera?.name.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.cameras)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(cameraExpanded, { cameraExpanded = false }) { cameras.forEach { item -> DropdownMenuItem({ Text(item.name) }, { camera = item; cameraExpanded = false }) } }
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.rule_name)) }, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(detectorExpanded, { detectorExpanded = it }) {
                OutlinedTextField(detector?.let { stringResource(detectorLabelRes(it.id)) }.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.detector)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(detectorExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                ExposedDropdownMenu(detectorExpanded, { detectorExpanded = false }) { detectors.forEach { item -> DropdownMenuItem({ Text(stringResource(detectorLabelRes(item.id))) }, { detector = item; detectorExpanded = false }) } }
            }
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
