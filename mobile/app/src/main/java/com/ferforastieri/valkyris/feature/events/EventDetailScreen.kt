@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ferforastieri.valkyris.feature.events

import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide

@Composable fun EventDetailScreen(onBack:()->Unit,vm:EventDetailViewModel=hiltViewModel()){
    val event by vm.event.collectAsStateWithLifecycle();val snapshot by vm.snapshot.collectAsStateWithLifecycle();val context=androidx.compose.ui.platform.LocalContext.current
    val player=remember(event?.clipPath){if(event?.clipPath==null)null else{val data=OkHttpDataSource.Factory(vm.httpClient()).setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${vm.token()}"));ExoPlayer.Builder(context).build().apply{setMediaSource(ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(vm.clipUrl())));prepare()}}}
    DisposableEffect(player){onDispose{player?.release()}}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Lucide.ArrowLeft,null)};Text(stringResource(R.string.event_detail),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)}
        val value=event
        if(value==null){Box(Modifier.fillMaxSize()){CircularProgressIndicator(Modifier.align(Alignment.Center))};return@Column}
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text(value.type.replace('_',' ').replaceFirstChar{it.uppercase()},style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            Text("${(value.confidence*100).toInt()}% · ${value.occurredAt}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot?.let{Surface(shape=RoundedCornerShape(20.dp)){Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().aspectRatio(16/9f),contentScale=ContentScale.Crop)}}
            if(player!=null)AndroidView(factory={PlayerView(it).apply{this.player=player;useController=true;layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)}},modifier=Modifier.fillMaxWidth().aspectRatio(16/9f))
            else Text(stringResource(R.string.media_processing),color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(value.acknowledgedAt==null)Button(onClick={vm.acknowledge()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.acknowledge))}
        }
    }
}
