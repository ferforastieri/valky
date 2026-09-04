@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ferforastieri.valkyris.feature.cameras

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import com.ferforastieri.valkyris.core.design.SignalLine
import com.ferforastieri.valkyris.core.design.ColorTokens
import com.ferforastieri.valkyris.core.design.cameraIcon
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
    var failedCameraId by remember{mutableStateOf<String?>(null)}
    val failedCamera=state.cameras.firstOrNull{it.id==failedCameraId}
    CamerasContent(state, onCamera={id->
        val camera=state.cameras.firstOrNull{it.id==id}
        if(camera?.setupStatus=="failed")failedCameraId=id else onCamera(id)
    }, onAdd = { if (!state.creating) showAdd = true })
    if(showAdd)AddCameraDialog(onDismiss={showAdd=false},onSave={vm.add(it);showAdd=false})
    failedCamera?.let{CameraFailureSheet(it){failedCameraId=null}}
}

@Composable
fun CamerasContent(state: CamerasState, onCamera: (String) -> Unit = {}, onAdd: () -> Unit = {}) {
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            Spacer(Modifier.height(10.dp))
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(state.creating){Spacer(Modifier.height(10.dp));LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.validating_camera),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.height(10.dp))
            when{
                state.loading->Box(Modifier.fillMaxSize()){CircularProgressIndicator(Modifier.align(Alignment.Center))}
                state.cameras.isEmpty()->EmptyCameras()
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=96.dp)){items(state.cameras,key={it.id}){camera->CameraCard(camera,state.snapshots[camera.id]){onCamera(camera.id)}}}
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp),
            containerColor = if (state.creating) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondary,
            contentColor = if (state.creating) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondary,
        ) {
            if (state.creating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Lucide.Plus, stringResource(R.string.add_camera))
        }
    }
}

@Composable
fun CameraFailureSheet(camera:Camera,onDismiss:()->Unit){
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title=stringResource(R.string.camera_error_title),
        onDismiss=onDismiss,
        actions={Button(onClick=onDismiss){Text(stringResource(R.string.close))}},
    ){
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.errorContainer){
                Icon(Lucide.VideoOff,null,Modifier.padding(14.dp).size(30.dp),tint=MaterialTheme.colorScheme.error)
            }
            Text(camera.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text(stringResource(R.string.camera_error_body),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.errorContainer){
                Text(camera.setupError.ifBlank{stringResource(R.string.camera_setup_failed)},Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun AddCameraDialog(onDismiss:()->Unit,onSave:(CreateCameraRequest)->Unit){
    var name by remember{mutableStateOf("")};var icon by remember{mutableStateOf("camera")};var host by remember{mutableStateOf("")};var port by remember{mutableStateOf("2020")};var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title = stringResource(R.string.add_camera),
        onDismiss = onDismiss,
        actions = {
            TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            Button(onClick={onSave(CreateCameraRequest(name=name,icon=icon,host=host,port=port.toIntOrNull()?:2020,username=username,password=password))},enabled=name.isNotBlank()&&host.isNotBlank()&&username.isNotBlank()&&password.isNotBlank()){Text(stringResource(R.string.save))}
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_name))})
            Text(stringResource(R.string.camera_icon),style=MaterialTheme.typography.labelLarge)
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                cameraIconOptions.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        row.forEach { option -> CameraIconChoice(option,icon==option.value,{icon=option.value},Modifier.weight(1f)) }
                        repeat(3-row.size){Spacer(Modifier.weight(1f))}
                    }
                }
            }
            OutlinedTextField(host,{host=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_ip))})
            OutlinedTextField(port,{port=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.onvif_port))})
            OutlinedTextField(username,{username=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_user))})
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_password))},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Text(stringResource(R.string.rtsp_automatic_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun CameraCard(camera: Camera, snapshot: android.graphics.Bitmap?, onClick: () -> Unit) {
    val ready = camera.setupStatus == "ready"
    val failed = camera.setupStatus == "failed"
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, if (failed) MaterialTheme.colorScheme.error.copy(alpha = .45f) else MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(ColorTokens.BrandTile)) {
                if (ready && snapshot != null) Image(snapshot.asImageBitmap(), camera.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else SignalLine(Modifier.fillMaxWidth().height(70.dp).align(Alignment.Center), if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                Surface(Modifier.padding(12.dp).align(Alignment.TopEnd), shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!ready && !failed) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        else Box(Modifier.size(7.dp).background(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ready) stringResource(R.string.snapshot_preview) else setupLabel(camera), style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else LocalContentColor.current)
                    }
                }
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(cameraIcon(camera.icon),null,Modifier.padding(10.dp).size(20.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(camera.name, fontWeight = FontWeight.SemiBold)
                    Text(if (ready) camera.host else setupDescription(camera), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                if (camera.capabilities.audio) Icon(Lucide.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (camera.capabilities.ptz) { Spacer(Modifier.width(8.dp)); Icon(Lucide.Move, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private data class CameraIconOption(val value:String,val label:Int,val image:ImageVector)

@Composable
private fun CameraIconChoice(option:CameraIconOption,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier){
    Surface(
        onClick=onClick,
        modifier=modifier.height(72.dp),
        shape=MaterialTheme.shapes.medium,
        color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
    ){
        Column(Modifier.padding(horizontal=4.dp,vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
            Icon(option.image,null,Modifier.size(23.dp),tint=if(selected)MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(option.label),style=MaterialTheme.typography.labelSmall,maxLines=1)
        }
    }
}

private val cameraIconOptions = listOf(
    CameraIconOption("camera",R.string.camera_icon_camera,cameraIcon("camera")),
    CameraIconOption("nursery",R.string.camera_icon_nursery,cameraIcon("nursery")),
    CameraIconOption("baby",R.string.camera_icon_baby,cameraIcon("baby")),
    CameraIconOption("bottle",R.string.camera_icon_bottle,cameraIcon("bottle")),
    CameraIconOption("dog",R.string.camera_icon_dog,cameraIcon("dog")),
    CameraIconOption("bedroom",R.string.camera_icon_bedroom,cameraIcon("bedroom")),
    CameraIconOption("office",R.string.camera_icon_office,cameraIcon("office")),
    CameraIconOption("entrance",R.string.camera_icon_entrance,cameraIcon("entrance")),
    CameraIconOption("living_room",R.string.camera_icon_living_room,cameraIcon("living_room")),
    CameraIconOption("yard",R.string.camera_icon_yard,cameraIcon("yard")),
    CameraIconOption("garage",R.string.camera_icon_garage,cameraIcon("garage")),
    CameraIconOption("kitchen",R.string.camera_icon_kitchen,cameraIcon("kitchen")),
    CameraIconOption("bathroom",R.string.camera_icon_bathroom,cameraIcon("bathroom")),
)

@Composable private fun setupLabel(camera: Camera) = when (camera.setupStatus) {
    "failed" -> stringResource(R.string.camera_setup_failed)
    else -> stringResource(R.string.camera_setup_saved)
}

@Composable private fun setupDescription(camera: Camera) = when (camera.setupStep) {
    "probing" -> stringResource(R.string.camera_setup_probing)
    "stream" -> stringResource(R.string.camera_setup_stream)
    "failed" -> camera.setupError.ifBlank { stringResource(R.string.camera_setup_failed) }
    else -> stringResource(R.string.camera_setup_queued)
}
@Composable private fun EmptyCameras(){Box(Modifier.fillMaxSize()){Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){Icon(Lucide.VideoOff,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));Text(stringResource(R.string.no_cameras),fontWeight=FontWeight.SemiBold);Text(stringResource(R.string.add_camera_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable
fun CameraLiveScreen(cameraId:String,onBack:()->Unit,vm:CameraLiveViewModel=hiltViewModel()) {
    val camera by vm.camera.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Lucide.ArrowLeft, null) }
            Column {
                Text(camera?.name ?: stringResource(R.string.live), fontWeight = FontWeight.SemiBold)
                Text(if (camera?.setupStatus == "ready") stringResource(R.string.stream_private) else stringResource(R.string.camera_setup_saved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (camera?.setupStatus) {
            "ready" -> ReadyCameraContent(requireNotNull(camera), vm)
            "failed" -> CameraSetupContent(requireNotNull(camera), failed = true)
            null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            else -> CameraSetupContent(requireNotNull(camera), failed = false)
        }
    }
}

@Composable
private fun CameraSetupContent(camera: Camera, failed: Boolean) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Card(Modifier.align(Alignment.Center).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, if (failed) MaterialTheme.colorScheme.error.copy(alpha = .45f) else MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (failed) Icon(Lucide.VideoOff, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator(Modifier.size(38.dp), strokeWidth = 3.dp)
                Text(if (failed) stringResource(R.string.camera_setup_failed) else setupDescription(camera), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (failed) {
                    Text(stringResource(R.string.camera_setup_error_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                        Text(camera.setupError.ifBlank { stringResource(R.string.camera_setup_failed) }, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                } else Text(stringResource(R.string.camera_setup_live_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReadyCameraContent(camera: Camera, vm: CameraLiveViewModel) {
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
    Box(Modifier.fillMaxSize()) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                LivePlayer(player, Modifier.weight(1f).fillMaxHeight())
                if (camera.capabilities.ptz) Box(Modifier.width(190.dp).fillMaxHeight()) { PTZPad(vm) }
            }
        } else {
            LivePlayer(player, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
            if (camera.capabilities.ptz) PTZPad(vm)
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
