package com.ferforastieri.valkyris.feature.people

import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
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
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.BoundingBox

@Composable
fun PeopleScreen(vm: PeopleViewModel = hiltViewModel()) {
    val users by vm.people.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var placeEditor by remember { mutableStateOf<TrackedPlace?>(null) }
    var selectedMapUser by remember { mutableStateOf<TrackedPerson?>(null) }
    var historyUser by remember { mutableStateOf<TrackedPerson?>(null) }
    var areaPickerOpen by remember { mutableStateOf(false) }
    var areasOpen by remember { mutableStateOf(false) }
    var deletingPlace by remember { mutableStateOf<TrackedPlace?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Localização", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Onde a família está agora", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(285.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            FamilyMap(
                users = users,
                onUserClick = { selectedMapUser = it },
                places = places,
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 98.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Família", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                if (users.isEmpty()) item { EmptyUsers() }
                items(users, key = { it.id }) { user ->
                    UserCard(user) { historyUser = user; vm.history(user) }
                }
                item {
                    TextButton(
                        onClick = { areasOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Lucide.MapPin, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (places.isEmpty()) "Áreas" else "Áreas (${places.size})")
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
    placeEditor?.let { place -> AreaEditorSheet(busy, GeoPoint(place.latitude, place.longitude), { placeEditor = null }, initial = place) { value ->
        val done: (Boolean) -> Unit = { if (it) placeEditor = null }
        if (value.id.isBlank()) vm.createPlace(value, done) else vm.updatePlace(value, done)
    } }
    if (areasOpen) AreasSheet(
        places = places,
        busy = busy,
        onDismiss = { areasOpen = false },
        onCreate = { areasOpen = false; areaPickerOpen = true },
        onEdit = { areasOpen = false; placeEditor = it },
        onDelete = { areasOpen = false; deletingPlace = it },
    )
    deletingPlace?.let { place ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingPlace = null },
            title = { Text("Remover área?") },
            text = { Text("A área “${place.name}” será removida permanentemente.") },
            dismissButton = { TextButton(onClick = { deletingPlace = null }, enabled = !busy) { Text("Cancelar") } },
            confirmButton = {
                Button(
                    onClick = { vm.deletePlace(place) { if (it) deletingPlace = null } },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Remover") }
            },
        )
    }
    selectedMapUser?.let { user ->
        ValkyrisBottomSheet(title = user.name, onDismiss = { selectedMapUser = null }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileAvatar(contentDescription = user.name, avatarData = user.avatarData, modifier = Modifier.size(52.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Sem localização recebida", style = MaterialTheme.typography.bodyMedium)
                    user.lastAccuracy?.let { Text("Precisão aproximada de ${it.toInt()} m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Button(onClick = { selectedMapUser = null; historyUser = user; vm.history(user) }, modifier = Modifier.fillMaxWidth()) { Text("Ver percurso") }
        }
    }
    historyUser?.let { user -> HistorySheet(user, history) { historyUser = null } }
}

@Composable
private fun FamilyMap(users: List<TrackedPerson>, places: List<TrackedPlace>, onUserClick: (TrackedPerson) -> Unit, modifier: Modifier = Modifier) {
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
                setOnMarkerClickListener { _, _ -> onUserClick(user); true }
                position = GeoPoint(lat, lon); title = user.name; snippet = user.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Sem atualização"
                icon = profileMarkerDrawable(map.context, user.avatarData)
                    ?: ContextCompat.getDrawable(map.context, R.drawable.valkyris_map_marker)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        val located = users.filter { it.lastLatitude?.isFinite() == true && it.lastLongitude?.isFinite() == true }
        val participantIds = located.map { it.id }.sorted()
        if (located.isNotEmpty() && map.tag != participantIds) {
            map.tag = participantIds
            val coordinates = located.map { GeoPoint(it.lastLatitude!!, it.lastLongitude!!) }
            map.post {
                if (map.tag == participantIds) {
                    if (coordinates.distinctBy { it.latitude to it.longitude }.size == 1) {
                        map.controller.setZoom(18.0)
                        map.controller.setCenter(coordinates.first())
                    } else {
                        val padding = (40 * map.resources.displayMetrics.density).toInt()
                        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(coordinates), false, padding, 18.0, null)
                    }
                }
            }
        }
        map.invalidate()
    }, onRelease = { it.onDetach() }, modifier = modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun AreaEditorSheet(
    busy: Boolean,
    initialCenter: GeoPoint?,
    onDismiss: () -> Unit,
    initial: TrackedPlace? = null,
    onSave: (TrackedPlace) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var radius by remember(initial?.id) { mutableStateOf(initial?.radiusMeters?.toInt()?.toString() ?: "100") }
    var point by remember(initial?.id) { mutableStateOf(initial?.let { GeoPoint(it.latitude, it.longitude) }) }
    val radiusMeters = radius.toDoubleOrNull()
    val validRadius = radiusMeters != null && radiusMeters in 20.0..5000.0

    ValkyrisBottomSheet(
        title = if (initial == null) "Cadastrar área" else "Editar área",
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        swipeToDismissEnabled = false,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
            Button(
                onClick = {
                    val selectedPoint = requireNotNull(point)
                    onSave(
                        (initial ?: TrackedPlace(name = "", latitude = selectedPoint.latitude, longitude = selectedPoint.longitude)).copy(
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
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                setOnTouchListener { view, event ->
                    // Keep the entire gesture in the map, including the first finger and pinch release.
                    val captureGesture = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                    var parent = view.parent
                    while (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(captureGesture)
                        parent = parent.parent
                    }
                    false
                }
                controller.setZoom(13.5)
                controller.setCenter(selectedPoint ?: initialCenter ?: GeoPoint(-23.5505, -46.6333))
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
            }
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
private fun UserCard(user: TrackedPerson, onHistory: () -> Unit) = Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onHistory),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(user.avatarData, user.name, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
            Text(user.name, fontWeight = FontWeight.SemiBold)
            Text(user.lastLocatedAt?.let { "Atualizado ${formatTime(it)}" } ?: "Ainda sem localização", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Lucide.History, "Abrir histórico", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AreasSheet(
    places: List<TrackedPlace>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (TrackedPlace) -> Unit,
    onDelete: (TrackedPlace) -> Unit,
) = ValkyrisBottomSheet(
    title = "Áreas",
    onDismiss = onDismiss,
    actions = { Button(onClick = onCreate, enabled = !busy) { Text("Cadastrar área") } },
) {
    if (places.isEmpty()) {
        Text("Nenhuma área cadastrada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            places.forEach { place ->
                ListItem(
                    headlineContent = { Text(place.name) },
                    supportingContent = { Text("Raio de ${place.radiusMeters.toInt()} m") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onEdit(place) }) { Icon(Lucide.Pencil, "Editar área") }
                            IconButton(onClick = { onDelete(place) }) { Icon(Lucide.Trash2, "Remover área", tint = MaterialTheme.colorScheme.error) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun HistorySheet(user: TrackedPerson, history: List<PersonLocation>, onDismiss: () -> Unit) = ValkyrisBottomSheet(title = "Por onde ${user.name} passou", onDismiss = onDismiss, swipeToDismissEnabled = false) {
    if (history.isEmpty()) Text("Ainda não há localização registrada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        val points = remember(history) { history.sortedBy { it.occurredAt } }
        var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
        val point = points[selected]
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        Column(
            Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.72f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ponto ${selected + 1} de ${points.size}", style = MaterialTheme.typography.titleMedium)
                    Text(formatHistoryTime(point.occurredAt), style = MaterialTheme.typography.bodyMedium)
                    Text("Precisão estimada: ${point.accuracy.toInt()} m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AndroidView(
                factory = { context ->
                    Configuration.getInstance().userAgentValue = context.packageName
                    MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        setOnTouchListener { view, event ->
                            view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                            false
                        }
                    }
                },
                update = { map ->
                    if (map.tag != points) {
                        map.tag = points
                        map.overlays.clear()
                        val coordinates = points.map { GeoPoint(it.latitude, it.longitude) }
                        map.overlays.add(Polyline(map).apply { setPoints(coordinates); outlinePaint.color = AndroidColor.rgb(87, 156, 78); outlinePaint.strokeWidth = 6f })
                        points.forEachIndexed { index, location ->
                            map.overlays.add(Marker(map).apply {
                                position = coordinates[index]
                                setOnMarkerClickListener { _, _ -> selected = index; true }
                                title = "Ponto ${index + 1}"
                                snippet = "${formatHistoryTime(location.occurredAt)} · precisão ${location.accuracy.toInt()} m"
                                icon = android.graphics.drawable.BitmapDrawable(map.resources, android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                                    val canvas = android.graphics.Canvas(bitmap)
                                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                    paint.color = AndroidColor.rgb(87, 156, 78)
                                    canvas.drawCircle(36f, 36f, 32f, paint)
                                    paint.color = AndroidColor.WHITE; paint.textSize = 28f; paint.textAlign = android.graphics.Paint.Align.CENTER
                                    canvas.drawText("${index + 1}", 36f, 36f - (paint.ascent() + paint.descent()) / 2, paint)
                                })
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            })
                        }
                        map.post {
                            if (map.tag == points) {
                                if (coordinates.size == 1) { map.controller.setZoom(17.0); map.controller.setCenter(coordinates.first()) }
                                else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(coordinates), false, 60, 17.0, null)
                            }
                        }
                        map.invalidate()
                    }
                },
                onRelease = { it.onDetach() },
                modifier = Modifier.fillMaxWidth().height((screenHeight * 0.36f).coerceIn(160.dp, 320.dp)).clip(RoundedCornerShape(16.dp)),
            )
            Text("Toque em um ponto no mapa ou na sequência abaixo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                points.forEachIndexed { index, _ ->
                    FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text("${index + 1}") })
                }
            }
        }
    }
}

private fun formatTime(value: String) = runCatching { DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
private fun formatHistoryTime(value: String) = runCatching { DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
