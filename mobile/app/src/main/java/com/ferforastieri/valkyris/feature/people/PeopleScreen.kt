package com.ferforastieri.valkyris.feature.people

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.UserRound
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.PersonLocation
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.core.model.TrackedPlace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun PeopleScreen(vm: PeopleViewModel = hiltViewModel()) {
    val users by vm.people.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var placeEditor by remember { mutableStateOf<TrackedPlace?>(null) }
    var editingUser by remember { mutableStateOf<TrackedPerson?>(null) }
    var historyUser by remember { mutableStateOf<TrackedPerson?>(null) }
    var selectingArea by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FamilyMap(
                users = users,
                places = places,
                selectingArea = selectingArea,
                onMapPoint = { point ->
                    if (selectingArea) {
                        selectingArea = false
                        placeEditor = TrackedPlace(name = "", latitude = point.latitude, longitude = point.longitude)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1.08f),
            )
            Surface(Modifier.fillMaxWidth().weight(.92f), color = MaterialTheme.colorScheme.background) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 98.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Família", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text("Perfis vinculados aos dispositivos autorizados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            me?.let { user -> IconButton(onClick = { editingUser = user }) { Icon(Lucide.SlidersHorizontal, "Editar meu perfil") } }
                        }
                    }
                    if (users.isEmpty()) item { EmptyUsers() }
                    items(users, key = { it.id }) { user -> UserCard(user, canEdit = user.id == me?.id, onEdit = { editingUser = user }) { historyUser = user; vm.history(user) } }
                    if (places.isNotEmpty()) {
                        item { Text("Áreas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp)) }
                        items(places, key = { it.id }) { place ->
                            ListItem(
                                headlineContent = { Text(place.name) },
                                supportingContent = { Text("Raio de ${place.radiusMeters.toInt()} m") },
                                modifier = Modifier.clickable { placeEditor = place },
                            )
                        }
                    }
                }
            }
        }
        if (selectingArea) Surface(Modifier.align(Alignment.TopCenter).padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 4.dp) {
            Text("Toque no mapa para posicionar a área", Modifier.padding(horizontal = 16.dp, vertical = 11.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        ExtendedFloatingActionButton(onClick = { selectingArea = !selectingArea }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp), icon = { Icon(if (selectingArea) Lucide.MapPin else Lucide.Plus, null) }, text = { Text(if (selectingArea) "Cancelar" else "Área") })
    }
    placeEditor?.let { place -> PlaceEditor(place, busy, { placeEditor = null }) { value ->
        val done: (Boolean) -> Unit = { if (it) placeEditor = null }
        if (value.id.isBlank()) vm.createPlace(value, done) else vm.updatePlace(value, done)
    } }
    editingUser?.let { user -> UserEditor(user, busy, { editingUser = null }) { value -> vm.updateMe(value) { if (it) editingUser = null } } }
    historyUser?.let { user -> HistorySheet(user, history) { historyUser = null } }
}

@Composable
private fun FamilyMap(users: List<TrackedPerson>, places: List<TrackedPlace>, selectingArea: Boolean, onMapPoint: (GeoPoint) -> Unit, modifier: Modifier = Modifier) {
    val latestMapPoint by rememberUpdatedState(onMapPoint)
    val latestSelectingArea by rememberUpdatedState(selectingArea)
    AndroidView(factory = { context ->
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(13.5); controller.setCenter(GeoPoint(-23.5505, -46.6333))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                    if (!latestSelectingArea) return false
                    point?.let { latestMapPoint(it) }
                    return true
                }
                override fun longPressHelper(point: GeoPoint?): Boolean {
                    if (!latestSelectingArea) return false
                    point?.let { latestMapPoint(it) }
                    return true
                }
            }))
        }
    }, update = { map ->
        map.overlays.removeAll { it is Marker || it is Polygon }
        places.forEach { place ->
            val circle = Polygon().apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(place.latitude, place.longitude), place.radiusMeters))
            }
            circle.fillColor = AndroidColor.argb(36, 91, 91, 214); circle.strokeColor = AndroidColor.rgb(91, 91, 214); circle.title = place.name
            map.overlays.add(circle)
        }
        users.forEach { user ->
            val lat = user.lastLatitude ?: return@forEach; val lon = user.lastLongitude ?: return@forEach
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(lat, lon); title = user.name; snippet = user.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Sem atualização"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        users.firstOrNull { it.lastLatitude != null && it.lastLongitude != null }?.let { map.controller.setCenter(GeoPoint(it.lastLatitude!!, it.lastLongitude!!)) }
        map.invalidate()
    }, modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun EmptyUsers() = Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Lucide.UserRound, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp)); Text("Nenhum dispositivo pareado ainda", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UserCard(user: TrackedPerson, canEdit: Boolean, onEdit: () -> Unit, onHistory: () -> Unit) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Lucide.UserRound, null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(user.name, fontWeight = FontWeight.SemiBold)
            Text(user.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Ainda sem localização", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (canEdit) IconButton(onClick = onEdit) { Icon(Lucide.SlidersHorizontal, "Editar perfil") }
        IconButton(onClick = onHistory) { Icon(Lucide.History, "Histórico de localização") }
    }
}

@Composable
private fun PlaceEditor(initial: TrackedPlace, busy: Boolean, onDismiss: () -> Unit, onSave: (TrackedPlace) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }; var radius by remember { mutableStateOf(initial.radiusMeters.takeIf { it > 0 }?.toInt()?.toString() ?: "100") }
    ValkyrisBottomSheet(title = "Cadastrar área", onDismiss = onDismiss, dismissEnabled = !busy, actions = {
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        Button(onClick = { onSave(initial.copy(name = name.trim(), radiusMeters = radius.toDoubleOrNull() ?: 0.0)) }, enabled = !busy && name.isNotBlank() && radius.toDoubleOrNull() != null) { Text("Salvar") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("O ponto foi escolhido no mapa. Você pode ajustar apenas o raio da área.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, label = { Text("Raio em metros") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UserEditor(initial: TrackedPerson, busy: Boolean, onDismiss: () -> Unit, onSave: (TrackedPerson) -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    ValkyrisBottomSheet(
        title = "Editar perfil",
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
            Button(onClick = { onSave(initial.copy(name = name.trim())) }, enabled = !busy && name.isNotBlank()) { Text("Salvar") }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Este perfil está vinculado a este celular.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistorySheet(user: TrackedPerson, history: List<PersonLocation>, onDismiss: () -> Unit) = ValkyrisBottomSheet(title = "Por onde ${user.name} passou", onDismiss = onDismiss) {
    if (history.isEmpty()) Text("Ainda não há localização registrada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { history.forEach { location -> ListItem(headlineContent = { Text("${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}") }, supportingContent = { Text(formatTime(location.occurredAt)) }, leadingContent = { Icon(Lucide.MapPin, null) }) } }
}

private fun formatTime(value: String) = runCatching { DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
