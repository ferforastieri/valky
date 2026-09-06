package com.ferforastieri.valkyris.feature.people

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UserRound
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.core.model.TrackedPlace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PeopleScreen(vm: PeopleViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val people by vm.people.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var personEditor by remember { mutableStateOf<TrackedPerson?>(null) }
    var placeEditor by remember { mutableStateOf<TrackedPlace?>(null) }
    var historyPerson by remember { mutableStateOf<TrackedPerson?>(null) }
    var pendingTracking by remember { mutableStateOf<TrackedPerson?>(null) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingTracking?.takeIf { granted }?.let { LocationTrackingService.start(context, it.id) }
        pendingTracking = null
    }
    fun startTracking(person: TrackedPerson) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) LocationTrackingService.start(context, person.id)
        else { pendingTracking = person; locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 110.dp),
        ) {
            item {
                Text("Pessoas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Localização compartilhada neste servidor", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (people.isEmpty()) item { EmptyPeople() }
            items(people, key = { it.id }) { person ->
                PersonCard(person, onTrack = { startTracking(person) }, onHistory = { historyPerson = person; vm.history(person) }, onDelete = { vm.deletePerson(person) })
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text("Áreas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Entre e saia de uma área para gerar um alerta", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (places.isEmpty()) item { Text("Nenhuma área cadastrada.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
            items(places, key = { it.id }) { place -> PlaceCard(place, onDelete = { vm.deletePlace(place) }) }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ExtendedFloatingActionButton(onClick = { placeEditor = TrackedPlace(name = "", latitude = 0.0, longitude = 0.0) }, icon = { Icon(Lucide.MapPin, null) }, text = { Text("Área") })
            ExtendedFloatingActionButton(onClick = { personEditor = TrackedPerson(name = "") }, icon = { Icon(Lucide.Plus, null) }, text = { Text("Pessoa") })
        }
    }
    personEditor?.let { person -> PersonEditor(person, busy, onDismiss = { personEditor = null }, onSave = { value ->
        if (person.id.isBlank()) vm.createPerson(value) { if (it) personEditor = null } else vm.updatePerson(value) { if (it) personEditor = null }
    }) }
    placeEditor?.let { place -> PlaceEditor(place, busy, onDismiss = { placeEditor = null }, onSave = { value ->
        if (place.id.isBlank()) vm.createPlace(value) { if (it) placeEditor = null } else vm.updatePlace(value) { if (it) placeEditor = null }
    }) }
    historyPerson?.let { person -> HistorySheet(person, history, onDismiss = { historyPerson = null }) }
}

@Composable private fun EmptyPeople() = Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Lucide.UserRound, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp)); Text("Cadastre uma pessoa para iniciar o rastreamento", fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun PersonCard(person: TrackedPerson, onTrack: () -> Unit, onHistory: () -> Unit, onDelete: () -> Unit) = Card(
    modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Lucide.UserRound, null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(person.name, fontWeight = FontWeight.SemiBold)
            Text(person.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Ainda sem localização", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            person.lastLatitude?.let { latitude -> Text("${"%.5f".format(latitude)}, ${"%.5f".format(person.lastLongitude ?: 0.0)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
        }
        IconButton(onClick = onTrack) { Icon(Lucide.MapPin, "Rastrear neste telefone") }
        IconButton(onClick = onHistory) { Icon(Lucide.History, "Histórico") }
        IconButton(onClick = onDelete) { Icon(Lucide.Trash2, "Remover") }
    }
}

@Composable private fun PlaceCard(place: TrackedPlace, onDelete: () -> Unit) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.MapPin, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(place.name, fontWeight = FontWeight.SemiBold); Text("Raio de ${place.radiusMeters.toInt()} m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }; IconButton(onClick = onDelete) { Icon(Lucide.Trash2, "Remover") }
    }
}

@Composable private fun PersonEditor(initial: TrackedPerson, busy: Boolean, onDismiss: () -> Unit, onSave: (TrackedPerson) -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    ValkyrisBottomSheet(title = if (initial.id.isBlank()) "Adicionar pessoa" else "Editar pessoa", onDismiss = onDismiss, dismissEnabled = !busy, actions = {
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        Button(onClick = { onSave(initial.copy(name = name.trim())) }, enabled = !busy && name.isNotBlank()) { Text("Salvar") }
    }) { OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
}

@Composable private fun PlaceEditor(initial: TrackedPlace, busy: Boolean, onDismiss: () -> Unit, onSave: (TrackedPlace) -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }; var latitude by remember(initial.id) { mutableStateOf(initial.latitude.takeIf { it != 0.0 }?.toString().orEmpty()) }; var longitude by remember(initial.id) { mutableStateOf(initial.longitude.takeIf { it != 0.0 }?.toString().orEmpty()) }; var radius by remember(initial.id) { mutableStateOf(initial.radiusMeters.toInt().toString()) }
    ValkyrisBottomSheet(title = if (initial.id.isBlank()) "Cadastrar área" else "Editar área", onDismiss = onDismiss, dismissEnabled = !busy, actions = {
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        Button(onClick = { onSave(initial.copy(name = name.trim(), latitude = latitude.toDoubleOrNull() ?: 0.0, longitude = longitude.toDoubleOrNull() ?: 0.0, radiusMeters = radius.toDoubleOrNull() ?: 0.0)) }, enabled = !busy && name.isNotBlank() && latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null && radius.toDoubleOrNull() != null) { Text("Salvar") }
    }) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(name,{name=it},label={Text("Nome")},singleLine=true,modifier=Modifier.fillMaxWidth()); OutlinedTextField(latitude,{latitude=it},label={Text("Latitude")},singleLine=true,modifier=Modifier.fillMaxWidth()); OutlinedTextField(longitude,{longitude=it},label={Text("Longitude")},singleLine=true,modifier=Modifier.fillMaxWidth()); OutlinedTextField(radius,{radius=it},label={Text("Raio em metros")},singleLine=true,modifier=Modifier.fillMaxWidth()) } }
}

@Composable private fun HistorySheet(person: TrackedPerson, history: List<PersonLocation>, onDismiss: () -> Unit) = ValkyrisBottomSheet(title = "Por onde ${person.name} passou", onDismiss = onDismiss) {
    if (history.isEmpty()) Text("Ainda não há localização registrada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { history.forEach { location -> ListItem(headlineContent = { Text("${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}") }, supportingContent = { Text(formatTime(location.occurredAt)) }, leadingContent = { Icon(Lucide.MapPin, null) }) } }
}

private fun formatTime(value: String) = runCatching { DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
