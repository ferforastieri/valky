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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.OperationalHeader
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.DetectorKind
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.RuleActions
import com.ferforastieri.valkyris.core.model.RuleSchedule
import java.time.ZoneId
import kotlin.math.roundToInt
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal

@Composable
fun RulesScreen(vm: RulesViewModel = hiltViewModel()) {
    val rules = vm.rules.collectAsStateWithLifecycle().value
    val cameras = vm.cameras.collectAsStateWithLifecycle().value
    val detectors = vm.detectors.collectAsStateWithLifecycle().value
    var show by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        OperationalHeader(
            icon = Lucide.SlidersHorizontal,
            eyebrow = stringResource(R.string.automation_status),
            title = stringResource(R.string.rules),
            metric = rules.size.toString(),
            status = stringResource(R.string.active_status),
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rules.size.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.active_status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { show = true }, enabled = cameras.isNotEmpty() && detectors.isNotEmpty()) {
                        Icon(Lucide.Plus, contentDescription = stringResource(R.string.add_rule))
                    }
                }
            }
        )
        Spacer(Modifier.height(18.dp))
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Lucide.SlidersHorizontal, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.no_rules), fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rules, key = { it.id }) { rule ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(rule.name, fontWeight = FontWeight.SemiBold)
                            Text(rule.detectorTypes.replace(",", " · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (show) QuickRuleDialog(cameras, detectors, onDismiss = { show = false }) {
        vm.create(it)
        show = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickRuleDialog(cameras: List<Camera>, detectors: List<DetectorKind>, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var camera by remember { mutableStateOf(cameras.firstOrNull()) }
    var detector by remember { mutableStateOf(detectors.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var confidence by remember { mutableFloatStateOf(.65f) }
    var confirmations by remember { mutableStateOf("2") }
    var cooldown by remember { mutableStateOf("60") }
    var scheduleStart by remember { mutableStateOf("") }
    var scheduleEnd by remember { mutableStateOf("") }
    var record by remember { mutableStateOf(true) }
    var notify by remember { mutableStateOf(true) }
    var alarm by remember { mutableStateOf(false) }
    var cameraExpanded by remember { mutableStateOf(false) }
    var detectorExpanded by remember { mutableStateOf(false) }
    val timePattern = remember { Regex("^([01]\\d|2[0-3]):[0-5]\\d$") }
    val scheduleValid = (scheduleStart.isBlank() && scheduleEnd.isBlank()) || (timePattern.matches(scheduleStart) && timePattern.matches(scheduleEnd))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_rule)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(cameraExpanded, { cameraExpanded = it }) {
                    OutlinedTextField(camera?.name.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.cameras)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(cameraExpanded, { cameraExpanded = false }) { cameras.forEach { item -> DropdownMenuItem({ Text(item.name) }, { camera = item; cameraExpanded = false }) } }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.rule_name)) }, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(detectorExpanded, { detectorExpanded = it }) {
                    OutlinedTextField(detector?.label.orEmpty(), {}, readOnly = true, label = { Text(stringResource(R.string.detector)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(detectorExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(detectorExpanded, { detectorExpanded = false }) { detectors.forEach { item -> DropdownMenuItem({ Text(item.label) }, { detector = item; detectorExpanded = false }) } }
                }
                Text("${stringResource(R.string.confidence)}: ${(confidence * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(confidence, { confidence = it }, valueRange = .4f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(confirmations, { confirmations = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.confirmations)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.cooldown_seconds)) }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(scheduleStart, { scheduleStart = it.take(5) }, label = { Text(stringResource(R.string.schedule_start)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(scheduleEnd, { scheduleEnd = it.take(5) }, label = { Text(stringResource(R.string.schedule_end)) }, modifier = Modifier.fillMaxWidth())
                RuleActionRow(record, { record = it }, stringResource(R.string.record_media))
                RuleActionRow(notify, { notify = it }, stringResource(R.string.send_notification))
                RuleActionRow(alarm, { alarm = it }, stringResource(R.string.sound_alarm))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val schedule = if (scheduleStart.isBlank()) RuleSchedule() else RuleSchedule((0..6).toList(), scheduleStart, scheduleEnd, ZoneId.systemDefault().id)
                    onSave(Rule(cameraId = checkNotNull(camera).id, name = name, detectorTypes = listOf(checkNotNull(detector).id), minConfidence = confidence.toDouble(), confirmations = confirmations.toIntOrNull() ?: 2, cooldownSeconds = cooldown.toIntOrNull() ?: 60, schedule = schedule, actions = RuleActions(record, notify, alarm)))
                },
                enabled = camera != null && detector != null && name.isNotBlank() && scheduleValid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RuleActionRow(checked: Boolean, onChecked: (Boolean) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, onChecked)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
