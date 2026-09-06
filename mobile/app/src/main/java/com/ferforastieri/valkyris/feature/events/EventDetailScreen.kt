@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ferforastieri.valkyris.feature.events

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.ferforastieri.valkyris.R
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EventDetailScreen(vm: EventDetailViewModel = hiltViewModel()) {
    val event by vm.event.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(event?.clipPath) {
        event?.takeIf { !it.clipPath.isNullOrBlank() }?.let {
            val data = OkHttpDataSource.Factory(vm.httpClient()).setDefaultRequestProperties(mapOf("Authorization" to ("Bearer " + vm.token())))
            ExoPlayer.Builder(context).build().apply {
                setMediaSource(ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(vm.clipUrl())))
                prepare()
                playWhenReady = true
            }
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    val value = event
    if (value == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (value.source == "tracking") {
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (value.type == "place_entered") "Chegou à área" else "Saiu da área", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${value.metadata["personName"]?.jsonPrimitive?.contentOrNull ?: "Pessoa"} · ${value.metadata["placeName"]?.jsonPrimitive?.contentOrNull ?: "Área"}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(eventTime(value.occurredAt), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else Surface(Modifier.fillMaxWidth().aspectRatio(16 / 9f), RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 5.dp) {
            if (player == null && value.clipStatus == "processing") {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.media_processing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (player == null) {
                Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(if (value.clipStatus == "failed") "Não foi possível preparar o clipe." else "Este alerta não tem clipe salvo.", fontWeight = FontWeight.SemiBold)
                    value.clipError?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (value.source == "tracking") "Alerta de localização" else stringResource(com.ferforastieri.valkyris.core.model.detectorLabelRes(value.type)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (value.source == "tracking") eventTime(value.occurredAt) else (value.confidence * 100).toInt().toString() + "% · " + eventTime(value.occurredAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value.acknowledgedAt == null) {
            Button(onClick = vm::acknowledge, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.acknowledge)) }
        }
    }
}

private fun eventTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
