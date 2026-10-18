package com.ferforastieri.valky.feature.overview

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ferforastieri.valky.R
import com.ferforastieri.valky.core.design.ValkyBottomSheet
import com.ferforastieri.valky.core.model.ActivityBucket
import com.ferforastieri.valky.core.model.ValkyEvent
import com.ferforastieri.valky.core.network.ValkyRepository
import com.ferforastieri.valky.feature.events.eventTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor(repository: ValkyRepository) : ViewModel() {
    val api = repository.api
}

@Composable
fun OverviewActivityChart(onEvent: (String) -> Unit, vm: ActivityViewModel = hiltViewModel()) {
    var hours by rememberSaveable { mutableIntStateOf(12) }
    var buckets by remember { mutableStateOf<List<ActivityBucket>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ActivityBucket?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hours, lifecycle) {
        buckets = null
        failed = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { buckets = vm.api.activity(hours); failed = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failed = error.message }
                // The chart aggregates 12-48 h buckets: refreshing it every few
                // seconds was pure round-trip waste.
                delay(60_000)
            }
        }
    }
    ActivityChart(hours, buckets, failed, onHours = { hours = it }, onBucket = { selected = it })
    selected?.let { bucket ->
        ActivitySheet(bucket, load = { vm.api.intervalEvents(bucket, it) }, onDismiss = { selected = null }, onEvent = { selected = null; onEvent(it) })
    }
}

@Composable
fun ActivityChart(hours: Int, buckets: List<ActivityBucket>?, failed: String? = null, onHours: (Int) -> Unit = {}, onBucket: (ActivityBucket) -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var periodExpanded by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.activity_chart_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { periodExpanded = true }) { Text("${hours}h ▾") }
                    DropdownMenu(expanded = periodExpanded, onDismissRequest = { periodExpanded = false }) {
                        listOf(12, 24, 36, 48).forEach { value ->
                            DropdownMenuItem(text = { Text("Últimas $value horas") }, onClick = { periodExpanded = false; onHours(value) }, trailingIcon = { if (hours == value) Text("✓") })
                        }
                    }
                }
            }
            failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (buckets == null && failed == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            buckets?.let { data ->
                Text(stringResource(R.string.activity_chart_count, data.sumOf { it.count }, hours / 12), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val max = data.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    data.forEach { bucket ->
                        val description = stringResource(R.string.activity_bar_description, activityTime(bucket.start), activityTime(bucket.end), bucket.count)
                        Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }.clickable(role = Role.Button) { onBucket(bucket) }, contentAlignment = Alignment.BottomCenter) {
                            if (bucket.count > 0) Box(Modifier.fillMaxWidth().fillMaxHeight((bucket.count.toFloat() / max).coerceAtLeast(.04f)).background(MaterialTheme.colorScheme.secondary.copy(alpha = .65f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                            else Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(data.firstOrNull()?.let { activityTime(it.start) }.orEmpty(), style = MaterialTheme.typography.labelSmall)
                    Text(data.lastOrNull()?.let { activityTime(it.end) }.orEmpty(), style = MaterialTheme.typography.labelSmall)
                }
                Text(stringResource(R.string.activity_chart_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ActivitySheet(bucket: ActivityBucket, load: suspend (Int) -> List<ValkyEvent>, onDismiss: () -> Unit, onEvent: (String) -> Unit) {
    var events by remember(bucket) { mutableStateOf(emptyList<ValkyEvent>()) }
    var offset by remember(bucket) { mutableIntStateOf(0) }
    var retry by remember(bucket) { mutableIntStateOf(0) }
    var loading by remember(bucket) { mutableStateOf(true) }
    var failed by remember(bucket) { mutableStateOf<String?>(null) }
    var more by remember(bucket) { mutableStateOf(false) }
    LaunchedEffect(bucket, offset, retry) {
        loading = true
        failed = null
        try {
            val page = load(offset)
            events = events + page
            more = page.size == 100
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failed = error.message }
        finally { loading = false }
    }
    ValkyBottomSheet(title = stringResource(R.string.activity_chart_title), onDismiss = onDismiss, scrollContent = false) {
        Text("${activityTime(bucket.start)} – ${activityTime(bucket.end)}", style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.activity_interval_count, bucket.count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events, key = { it.id }) { event ->
                OutlinedCard(onClick = { onEvent(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(eventTitle(event), style = MaterialTheme.typography.titleSmall)
                        Text(activityTime(event.occurredAt), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    failed != null -> { Text(failed!!); TextButton(onClick = { retry++ }) { Text(stringResource(R.string.activity_retry)) } }
                    more -> TextButton(onClick = { loading = true; offset += 100 }) { Text(stringResource(R.string.activity_more)) }
                    events.isEmpty() -> Text(stringResource(R.string.activity_empty), Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

// O servidor pode mandar uma data vazia ou malformada; parse direto na composição
// derrubava a tela inicial, então devolvemos o valor original como fallback.
private fun activityTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
