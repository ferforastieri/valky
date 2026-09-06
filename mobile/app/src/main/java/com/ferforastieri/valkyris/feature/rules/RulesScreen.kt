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
import com.composables.icons.lucide.EllipsisVertical
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
    var managing by remember { mutableStateOf<Rule?>(null) }
    var editing by remember { mutableStateOf<Rule?>(null) }

    RulesContent(rules, cameras.isNotEmpty() && detectors.isNotEmpty(), saving, onAdd = { creating = true }, onManage = { managing = it })
    if (creating) {
        RuleEditorDialog(cameras, detectors, saving = saving, onDismiss = { if (!saving) creating = false }) {
            vm.create(it) { success -> if (success) creating = false }
        }
    }
    editing?.let { existing ->
        RuleEditorDialog(cameras, detectors, existing, saving, onDismiss = { if (!saving) editing = null }) {
            vm.update(existing.id, it) { success -> if (success) editing = null }
        }
    }
    managing?.let { rule ->
        RuleOptionsSheet(
            rule = rule,
            busy = saving,
            onDismiss = { if (!saving) managing = null },
            onEdit = { editing = rule; managing = null },
            onDelete = { vm.delete(rule.id) { success -> if (success) managing = null } },
        )
    }
}

@Composable
fun RulesContent(
    rules: List<Rule>,
    canAdd: Boolean = true,
    saving: Boolean = false,
    onAdd: () -> Unit = {},
    onManage: (Rule) -> Unit = {},
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
                    items(rules, key = { it.id }) { rule -> RuleCard(rule, onManage = { onManage(rule) }) }
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
private fun RuleCard(rule: Rule, onManage: () -> Unit) {
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
                    Surface(shape = MaterialTheme.shapes.small, color = if (rule.enabled) MaterialTheme.colorScheme.secondary.copy(alpha = .18f) else MaterialTheme.colorScheme.surfaceVariant) {
                        Text(stringResource(if (rule.enabled) R.string.rule_enabled else R.string.rule_disabled), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onManage) { Icon(Lucide.EllipsisVertical, stringResource(R.string.edit_rule)) }
                }
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RuleOptionsSheet(rule: Rule, busy: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title = rule.name,
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        actions = {
            TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.edit_rule)) }
            Button(onClick = onDelete, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                else Icon(Lucide.Trash2, null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.remove_rule))
            }
        },
    ) {
        Text(stringResource(R.string.rule_detects, rule.detectorTypes.joinToString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(cameras: List<Camera>, detectors: List<DetectorKind>, existing: Rule? = null, saving: Boolean, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
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
                        cameraId = checkNotNull(camera).id,
                        name = name,
                        detectorTypes = listOf(checkNotNull(detector).id),
                        minConfidence = existing?.minConfidence ?: .65,
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
            ExposedDropdownMenuBox(cameraExpanded, { cameraExpanded = it }) {
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, onChecked)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
