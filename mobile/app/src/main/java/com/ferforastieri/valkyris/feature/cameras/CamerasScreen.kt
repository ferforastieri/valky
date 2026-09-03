@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ferforastieri.valkyris.feature.cameras

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.OperationalHeader
import com.ferforastieri.valkyris.core.design.SignalLine
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Move
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.VideoOff

@Composable fun CamerasScreen(onCamera:(String)->Unit,vm:CamerasViewModel=hiltViewModel()){
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember{mutableStateOf(false)}
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            Spacer(Modifier.height(18.dp))
            OperationalHeader(
                icon = Lucide.Video,
                eyebrow = stringResource(R.string.monitoring_local),
                title = stringResource(R.string.cameras),
                metric = state.cameras.size.toString(),
                status = stringResource(R.string.connected_status),
            )
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(state.creating){Spacer(Modifier.height(10.dp));LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.validating_camera),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.height(18.dp))
            when{
                state.loading->Box(Modifier.fillMaxSize()){CircularProgressIndicator(Modifier.align(Alignment.Center))}
                state.cameras.isEmpty()->EmptyCameras()
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=96.dp)){items(state.cameras,key={it.id}){CameraCard(it){onCamera(it.id)}}}
            }
        }
        FloatingActionButton(
            onClick = { if (!state.creating) showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = if (state.creating) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            contentColor = if (state.creating) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
        ) {
            if (state.creating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Lucide.Plus, stringResource(R.string.add_camera))
        }
    }
    if(showAdd)AddCameraDialog(onDismiss={showAdd=false},onSave={vm.add(it);showAdd=false})
}

@Composable private fun AddCameraDialog(onDismiss:()->Unit,onSave:(CreateCameraRequest)->Unit){
    var name by remember{mutableStateOf("")};var host by remember{mutableStateOf("")};var port by remember{mutableStateOf("2020")};var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var rtsp by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.add_camera))},text={Column(Modifier.fillMaxWidth().heightIn(max=480.dp).imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_name))});OutlinedTextField(host,{host=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_ip))});OutlinedTextField(port,{port=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.onvif_port))});OutlinedTextField(username,{username=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_user))});OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_password))},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation());OutlinedTextField(rtsp,{rtsp=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.rtsp_uri))})}},confirmButton={Button(onClick={onSave(CreateCameraRequest(name,host,port.toIntOrNull()?:2020,username,password,rtsp))},enabled=name.isNotBlank()&&host.isNotBlank()&&username.isNotBlank()&&password.isNotBlank()&&rtsp.startsWith("rtsp://")){Text(stringResource(R.string.save))}},dismissButton={TextButton(onDismiss){Text(stringResource(R.string.cancel))}})
}
@Composable private fun CameraCard(camera:Camera,onClick:()->Unit){Card(onClick=onClick,Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),elevation=CardDefaults.cardElevation(defaultElevation=6.dp)){Column{Box(Modifier.fillMaxWidth().aspectRatio(16/8f).background(MaterialTheme.colorScheme.primary)){SignalLine(Modifier.fillMaxWidth().height(70.dp).align(Alignment.Center),MaterialTheme.colorScheme.secondary);Surface(Modifier.padding(12.dp).align(Alignment.TopEnd),shape=CircleShape,color=MaterialTheme.colorScheme.surface.copy(alpha=.88f)){Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.secondary,CircleShape));Spacer(Modifier.width(6.dp));Text("LIVE",style=MaterialTheme.typography.labelSmall)}}};Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(camera.name,fontWeight=FontWeight.SemiBold);Text(camera.host,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)};if(camera.capabilities.audio)Icon(Lucide.Mic,null,tint=MaterialTheme.colorScheme.onSurfaceVariant);if(camera.capabilities.ptz){Spacer(Modifier.width(8.dp));Icon(Lucide.Move,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
@Composable private fun EmptyCameras(){Box(Modifier.fillMaxSize()){Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){Icon(Lucide.VideoOff,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));Text(stringResource(R.string.no_cameras),fontWeight=FontWeight.SemiBold);Text(stringResource(R.string.add_camera_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable
fun CameraLiveScreen(cameraId:String,onBack:()->Unit,vm:CameraLiveViewModel=hiltViewModel()) {
    val camera by vm.camera.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val player = remember {
        val factory = OkHttpDataSource.Factory(vm.httpClient()).setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${vm.token()}"))
        ExoPlayer.Builder(context).setMediaSourceFactory(HlsMediaSource.Factory(factory)).build().apply {
            setMediaItem(MediaItem.fromUri(vm.liveUrl()))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            player.release()
            vm.stop()
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Lucide.ArrowLeft, null) }
            Column {
                Text(camera?.name ?: stringResource(R.string.live), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.stream_private), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LivePlayer(player, Modifier.weight(1f).fillMaxHeight())
                if (camera?.capabilities?.ptz == true) Box(Modifier.width(190.dp).fillMaxHeight()) { PTZPad(vm) }
            }
        } else {
            LivePlayer(player, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
            if (camera?.capabilities?.ptz == true) PTZPad(vm)
        }
    }
}

@Composable
private fun LivePlayer(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } },
        modifier = modifier,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
@Composable private fun PTZPad(vm:CameraLiveViewModel){Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){PTZButton(icon=Lucide.ChevronUp,onPress={vm.move(0.0,.65)},onRelease={vm.stop()});Row(verticalAlignment=Alignment.CenterVertically){PTZButton(icon=Lucide.ChevronLeft,onPress={vm.move(-.65,0.0)},onRelease={vm.stop()});Spacer(Modifier.width(56.dp));PTZButton(icon=Lucide.ChevronRight,onPress={vm.move(.65,0.0)},onRelease={vm.stop()})};PTZButton(icon=Lucide.ChevronDown,onPress={vm.move(0.0,-.65)},onRelease={vm.stop()})}}
@Composable private fun PTZButton(icon:androidx.compose.ui.graphics.vector.ImageVector,onPress:()->Unit,onRelease:()->Unit){Surface(Modifier.size(52.dp).pointerInput(Unit){detectTapGestures(onPress={onPress();tryAwaitRelease();onRelease()})},shape=CircleShape,color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp){Box{Icon(icon,null,Modifier.align(Alignment.Center))}}}
