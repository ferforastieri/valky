package com.ferforastieri.valkyris.feature.people

import android.graphics.Color as AndroidColor
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.UserRound
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.design.ProfileAvatar
import com.ferforastieri.valkyris.core.design.profileMarkerDrawable
import com.ferforastieri.valkyris.R
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
    var historyUser by remember { mutableStateOf<TrackedPerson?>(null) }
    var areaPickerOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FamilyMap(
                users = users,
                places = places,
                modifier = Modifier.fillMaxWidth().weight(1.08f),
            )
            Surface(Modifier.fillMaxWidth().weight(.92f), color = MaterialTheme.colorScheme.background) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 98.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Button(
                            onClick = { areaPickerOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Lucide.MapPin, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cadastrar área")
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Família", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text("Perfis vinculados aos dispositivos autorizados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (users.isEmpty()) item { EmptyUsers() }
                    items(users, key = { it.id }) { user -> UserCard(user) { historyUser = user; vm.history(user) } }
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
    }
    if (areaPickerOpen) AreaEditorSheet(
        busy = busy,
        initialCenter = me?.takeIf { it.lastLatitude != null && it.lastLongitude != null }
            ?.let { GeoPoint(it.lastLatitude!!, it.lastLongitude!!) },
        onDismiss = { areaPickerOpen = false },
        onSave = { value ->
            vm.createPlace(value) { created ->
                if (created) areaPickerOpen = false
            }
        },
    )
    placeEditor?.let { place -> PlaceEditor(place, busy, { placeEditor = null }) { value ->
        val done: (Boolean) -> Unit = { if (it) placeEditor = null }
        if (value.id.isBlank()) vm.createPlace(value, done) else vm.updatePlace(value, done)
    } }
    historyUser?.let { user -> HistorySheet(user, history) { historyUser = null } }
}

@Composable
private fun FamilyMap(users: List<TrackedPerson>, places: List<TrackedPlace>, modifier: Modifier = Modifier) {
    AndroidView(factory = { context ->
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(13.5); controller.setCenter(GeoPoint(-23.5505, -46.6333))
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
                icon = profileMarkerDrawable(map.context, user.avatarData)
                    ?: ContextCompat.getDrawable(map.context, R.drawable.valkyris_map_marker)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        users.firstOrNull { it.lastLatitude != null && it.lastLongitude != null }?.let { map.controller.setCenter(GeoPoint(it.lastLatitude!!, it.lastLongitude!!)) }
        map.invalidate()
    }, modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun AreaEditorSheet(
    busy: Boolean,
    initialCenter: GeoPoint?,
    onDismiss: () -> Unit,
    onSave: (TrackedPlace) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var point by remember { mutableStateOf<GeoPoint?>(null) }
    val radiusMeters = radius.toDoubleOrNull()
    val validRadius = radiusMeters != null && radiusMeters in 20.0..5000.0

    ValkyrisBottomSheet(
        title = "Cadastrar área",
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
            Button(
                onClick = {
                    val selectedPoint = requireNotNull(point)
                    onSave(
                        TrackedPlace(
                            name = name.trim(),
                            latitude = selectedPoint.latitude,
                            longitude = selectedPoint.longitude,
                            radiusMeters = requireNotNull(radiusMeters),
                        ),
                    )
                },
                enabled = !busy && name.isNotBlank() && validRadius && point != null,
            ) { Text("Salvar") }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = radius,
                onValueChange = { radius = it.filter(Char::isDigit) },
                label = { Text("Raio em metros") },
                supportingText = { Text("De 20 a 5.000 m") },
                isError = radius.isNotBlank() && !validRadius,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Toque diretamente no mapa para definir o local.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AreaPickerMap(
                selectedPoint = point,
                initialCenter = initialCenter,
                onPointSelected = { point = it },
                modifier = Modifier.fillMaxWidth().height(250.dp).clip(MaterialTheme.shapes.large),
            )
        }
    }
}

@Composable
private fun AreaPickerMap(
    selectedPoint: GeoPoint?,
    initialCenter: GeoPoint?,
    onPointSelected: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPointSelected by rememberUpdatedState(onPointSelected)
    AndroidView(
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(13.5)
                initialCenter?.let { controller.setCenter(it) }
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                        point?.let(currentOnPointSelected)
                        return point != null
                    }

                    override fun longPressHelper(point: GeoPoint?): Boolean = false
                }))
            }
        },
        update = { map ->
            map.overlays.removeAll { it is Marker }
            selectedPoint?.let { point ->
                map.overlays.add(Marker(map).apply {
                    position = point
                    title = "Local da área"
                    icon = ContextCompat.getDrawable(map.context, R.drawable.valkyris_map_marker)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
                map.controller.setCenter(point)
            }
            if (selectedPoint == null) initialCenter?.let { map.controller.setCenter(it) }
            map.invalidate()
        },
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun EmptyUsers() = Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Lucide.UserRound, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp)); Text("Nenhum dispositivo pareado ainda", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UserCard(user: TrackedPerson, onHistory: () -> Unit) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(user.avatarData, user.name, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(user.name, fontWeight = FontWeight.SemiBold)
            Text(user.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Ainda sem localização", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onHistory) { Icon(Lucide.History, "Histórico de localização") }
    }
}

@Composable
private fun PlaceEditor(initial: TrackedPlace, busy: Boolean, onDismiss: () -> Unit, onSave: (TrackedPlace) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }; var radius by remember { mutableStateOf(initial.radiusMeters.takeIf { it > 0 }?.toInt()?.toString() ?: "100") }
    val radiusMeters = radius.toDoubleOrNull()
    val validRadius = radiusMeters != null && radiusMeters in 20.0..5000.0
    ValkyrisBottomSheet(title = "Cadastrar área", onDismiss = onDismiss, dismissEnabled = !busy, actions = {
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        Button(onClick = { onSave(initial.copy(name = name.trim(), radiusMeters = requireNotNull(radiusMeters))) }, enabled = !busy && name.isNotBlank() && validRadius) { Text("Salvar") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("O ponto foi escolhido no mapa. Você pode ajustar apenas o raio da área.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, label = { Text("Raio em metros") }, supportingText = { Text("De 20 a 5.000 m") }, isError = radius.isNotBlank() && !validRadius, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HistorySheet(user: TrackedPerson, history: List<PersonLocation>, onDismiss: () -> Unit) = ValkyrisBottomSheet(title = "Por onde ${user.name} passou", onDismiss = onDismiss) {
    if (history.isEmpty()) Text("Ainda não há localização registrada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { history.forEach { location -> ListItem(headlineContent = { Text("${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}") }, supportingContent = { Text(formatTime(location.occurredAt)) }, leadingContent = { Icon(Lucide.MapPin, null) }) } }
}

private fun formatTime(value: String) = runCatching { DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
