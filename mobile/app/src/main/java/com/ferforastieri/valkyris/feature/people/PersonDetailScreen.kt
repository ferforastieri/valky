package com.ferforastieri.valkyris.feature.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: String = checkNotNull(saved["id"])
    val people = repository.people
    val places = repository.places
    private val _history = MutableStateFlow<List<PersonLocation>>(emptyList())
    val history = _history.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.refreshUsers() }
            runCatching { repository.refreshPlaces() }
        }
        load()
    }

    fun load() {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _history.value = repository.api.userHistory(id, 0, "", 100)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message
            } finally {
                _loading.value = false
            }
        }
    }
}

private data class DayStats(val points: Int, val first: String, val last: String, val distanceMeters: Double)

@Composable
fun PersonDetailScreen(onBack: () -> Unit, vm: PersonDetailViewModel = hiltViewModel()) {
    val users by vm.people.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val user = users.firstOrNull { it.id == vm.id }
    var day by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_EXPRESSION") error

    val days = remember(history) {
        val fromData = history.map { it.occurredAt.take(10) }
        val recent = (0..13).map { java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(it.toLong()).toString() }
        (fromData + recent).distinct().sortedDescending()
    }
    val selectedDay = day
    val filtered = remember(history, selectedDay) { if (selectedDay == null) history else history.filter { it.occurredAt.startsWith(selectedDay) } }
    val stats = remember(filtered) { statsOf(filtered) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(user?.name ?: "Localização", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    lastSeenLabel(user?.lastLocatedAt) + if (user != null && user.battery in 0..100) " · Bateria ${user.battery}%" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(230.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            FamilyMap(
                users = listOfNotNull(user),
                places = places,
                route = filtered,
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge),
            )
        }
        if (loading && history.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        if (days.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = day == null, onClick = { day = null }, label = { Text("Tudo") })
                days.take(21).forEach { value ->
                    FilterChip(selected = day == value, onClick = { day = value }, label = { Text(dayLabel(value)) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Pontos", stats.points.toString(), Modifier.weight(1f))
            StatChip("Distância", distanceLabel(stats.distanceMeters), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("Primeiro", stats.first.ifBlank { "—" }, Modifier.weight(1f))
            StatChip("Último", stats.last.ifBlank { "—" }, Modifier.weight(1f))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
            contentPadding = PaddingValues(bottom = 98.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Histórico detalhado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (filtered.isEmpty()) {
                item { Text(if (loading) "Carregando…" else "Nenhum registro neste período.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            items(filtered, key = { it.id }) { point ->
                Surface(
                    Modifier.fillMaxWidth(),
                    RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.MapPin, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(point.address.ifBlank { "%.5f, %.5f".format(point.latitude, point.longitude) }, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            Text("Precisão ${point.accuracy.toInt()} m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(historyTime(point.occurredAt), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier) {
    Surface(modifier, RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun statsOf(points: List<PersonLocation>): DayStats {
    if (points.isEmpty()) return DayStats(0, "", "", 0.0)
    val sorted = points.sortedBy { it.occurredAt }
    var meters = 0.0
    for (index in 1 until sorted.size) {
        meters += haversine(sorted[index - 1].latitude, sorted[index - 1].longitude, sorted[index].latitude, sorted[index].longitude)
    }
    return DayStats(sorted.size, historyTime(sorted.first().occurredAt), historyTime(sorted.last().occurredAt), meters)
}

private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun distanceLabel(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)

private fun dayLabel(value: String) = runCatching {
    java.time.LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM"))
}.getOrDefault(value)

private fun historyTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd/MM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun lastSeenLabel(value: String?): String {
    if (value.isNullOrBlank()) return "Sem localização recente"
    return "Visto " + runCatching {
        DateTimeFormatter.ofPattern("dd/MM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    }.getOrDefault(value)
}
