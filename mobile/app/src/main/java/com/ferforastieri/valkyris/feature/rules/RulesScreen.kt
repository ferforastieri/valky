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
import com.ferforastieri.valkyris.core.model.RuleSchedule
import java.time.ZoneId
import kotlin.math.roundToInt
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Video

@Composable
fun RulesScreen(vm: RulesViewModel = hiltViewModel()) {
    val rules = vm.rules.collectAsStateWithLifecycle().value
    val cameras = vm.cameras.collectAsStateWithLifecycle().value
    val detectors = vm.detectors.collectAsStateWithLifecycle().value
    var show by remember { mutableStateOf(false) }
    RulesContent(rules, cameras.isNotEmpty() && detectors.isNotEmpty(), onAdd = { show = true })
    if (show) QuickRuleDialog(cameras, detectors, onDismiss = { show = false }) {
        vm.create(it)
        show = false
    }
}

@Composable
fun RulesContent(
    rules: List<com.ferforastieri.valkyris.core.database.RuleEntity>,
    canAdd: Boolean = true,
    onAdd: () -> Unit = {},
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
                items(rules, key = { it.id }) { rule ->
                    RuleCard(rule)
                }
            }
        }
      }
      if (canAdd) {
          FloatingActionButton(
              onClick = onAdd,
              modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 80.dp),
              containerColor = MaterialTheme.colorScheme.secondary,
              contentColor = MaterialTheme.colorScheme.onSecondary,
          ) { Icon(Lucide.Plus, contentDescription = stringResource(R.string.add_rule)) }
      }
    }
}

@Composable
private fun RuleCard(rule: com.ferforastieri.valkyris.core.database.RuleEntity) {
    val identifiers = rule.detectorTypes.split(',').map(String::trim).filter(String::isNotBlank)
    val detectorNames = mutableListOf<String>()
    for (identifier in identifiers) detectorNames += detectorLabel(identifier)
    val critical = identifiers.any { it in setOf("scream", "glass_break", "smoke_alarm", "fire_alarm", "siren", "tamper") }
    val motion = identifiers.any { it == "motion" || it == "person" || it == "tamper" }
    val icon: ImageVector = when {
        critical -> Lucide.TriangleAlert
        motion -> Lucide.Video
        else -> Lucide.Mic
    }
    val accent: Color = if (critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    val description = stringResource(
        when {
            critical -> R.string.rule_critical_description
            motion -> R.string.rule_motion_description
            else -> R.string.rule_audio_description
        },
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = accent.copy(alpha = .14f),
            ) {
                Icon(icon, null, Modifier.padding(11.dp).size(23.dp), tint = accent)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (rule.enabled) MaterialTheme.colorScheme.secondary.copy(alpha = .18f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            stringResource(if (rule.enabled) R.string.rule_enabled else R.string.rule_disabled),
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.rule_detects, detectorNames.joinToString(" · ")),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun detectorLabel(identifier: String): String = stringResource(
    when (identifier) {
        "motion" -> R.string.detector_motion
        "person" -> R.string.detector_person
        "tamper" -> R.string.detector_tamper
        "baby_cry" -> R.string.detector_baby_cry
        "crying" -> R.string.detector_crying
        "scream" -> R.string.detector_scream
        "glass_break" -> R.string.detector_glass_break
        "smoke_alarm" -> R.string.detector_smoke_alarm
        "fire_alarm" -> R.string.detector_fire_alarm
        "siren" -> R.string.detector_siren
        "doorbell" -> R.string.detector_doorbell
        "knock" -> R.string.detector_knock
        "dog_bark" -> R.string.detector_dog_bark
        else -> R.string.detector_other
    },
)

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

    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title = stringResource(R.string.add_rule),
        onDismiss = onDismiss,
        actions = {
            TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    val schedule = if (scheduleStart.isBlank()) RuleSchedule() else RuleSchedule((0..6).toList(), scheduleStart, scheduleEnd, ZoneId.systemDefault().id)
                    onSave(Rule(cameraId = checkNotNull(camera).id, name = name, detectorTypes = listOf(checkNotNull(detector).id), minConfidence = confidence.toDouble(), confirmations = confirmations.toIntOrNull() ?: 2, cooldownSeconds = cooldown.toIntOrNull() ?: 60, schedule = schedule, actions = RuleActions(record, notify, alarm)))
                },
                enabled = camera != null && detector != null && name.isNotBlank() && scheduleValid,
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
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
