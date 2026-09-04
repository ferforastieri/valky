@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ferforastieri.valkyris.feature.cameras

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.composables.icons.lucide.Aperture
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Move
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.VideoOff
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX

@Composable fun CamerasScreen(onCamera:(String)->Unit,vm:CamerasViewModel=hiltViewModel()){
    LifecycleResumeEffect(Unit) { vm.refresh();onPauseOrDispose {} }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember{mutableStateOf(false)}
    var failedCameraId by remember{mutableStateOf<String?>(null)}
    var managedCameraId by remember{mutableStateOf<String?>(null)}
    val failedCamera=state.cameras.firstOrNull{it.id==failedCameraId}
    val managedCamera=state.cameras.firstOrNull{it.id==managedCameraId}
    CamerasContent(state, onCamera={id->
        val camera=state.cameras.firstOrNull{it.id==id}
        if(camera?.setupStatus=="failed")failedCameraId=id else onCamera(id)
    }, onManage={managedCameraId=it}, onAdd = { if (!state.creating) showAdd = true })
    if(showAdd)AddCameraDialog(onDismiss={showAdd=false},onSave={vm.add(it);showAdd=false})
    failedCamera?.let{camera->CameraFailureSheet(camera,onDismiss={failedCameraId=null},onDelete={vm.delete(camera.id);failedCameraId=null})}
    managedCamera?.let{camera->CameraOptionsSheet(camera,camera.id in state.deleting,onDismiss={managedCameraId=null},onDelete={vm.delete(camera.id);managedCameraId=null})}
}

@Composable
fun CamerasContent(state: CamerasState, onCamera: (String) -> Unit = {}, onManage: (String) -> Unit = {}, onAdd: () -> Unit = {}) {
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            Spacer(Modifier.height(10.dp))
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(state.creating){Spacer(Modifier.height(10.dp));LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.validating_camera),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.height(10.dp))
            when{
                state.loading->Box(Modifier.fillMaxSize()){CircularProgressIndicator(Modifier.align(Alignment.Center))}
                state.cameras.isEmpty()->EmptyCameras()
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=96.dp)){items(state.cameras,key={it.id}){camera->CameraCard(camera,state.snapshots[camera.id],onClick={onCamera(camera.id)},onManage={onManage(camera.id)})}}
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
fun CameraFailureSheet(camera:Camera,onDismiss:()->Unit,onDelete:(()->Unit)?=null){
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title=stringResource(R.string.camera_error_title),
        onDismiss=onDismiss,
        actions={
            onDelete?.let{TextButton(onClick=it,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Lucide.Trash2,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.remove_camera))}}
            Button(onClick=onDismiss){Text(stringResource(R.string.close))}
        },
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

@Composable
private fun CameraOptionsSheet(camera:Camera,deleting:Boolean,onDismiss:()->Unit,onDelete:()->Unit){
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title=stringResource(R.string.camera_options),
        onDismiss=onDismiss,
        dismissEnabled=!deleting,
        actions={
            Button(
                onClick=onDelete,
                enabled=!deleting,
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError),
            ){
                if(deleting)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onError)
                else Icon(Lucide.Trash2,null)
                Spacer(Modifier.width(7.dp))
                Text(if(deleting)stringResource(R.string.removing_camera) else stringResource(R.string.remove_camera))
            }
        },
    ){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.secondaryContainer){
                Icon(cameraIcon(camera.icon),null,Modifier.padding(14.dp).size(30.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)){
                Text(camera.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(camera.host,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.remove_camera_body),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                cameraIconOptions.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        row.forEach { option -> CameraIconChoice(option,icon==option.value,{icon=option.value},Modifier.weight(1f)) }
                        repeat(2-row.size){Spacer(Modifier.weight(1f))}
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
private fun CameraCard(camera: Camera, snapshot: android.graphics.Bitmap?, onClick: () -> Unit, onManage: () -> Unit) {
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
                IconButton(onClick=onManage){Icon(Lucide.EllipsisVertical,stringResource(R.string.camera_options),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
    }
}

private data class CameraIconOption(val value:String,val label:Int,val image:ImageVector)

@Composable
private fun CameraIconChoice(option:CameraIconOption,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier){
    Surface(
        onClick=onClick,
        modifier=modifier.height(92.dp),
        shape=MaterialTheme.shapes.medium,
        color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
    ){
        Box(Modifier.fillMaxSize().padding(horizontal=10.dp,vertical=9.dp)){
            Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
                Surface(shape=CircleShape,color=if(selected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface){
                    Icon(option.image,null,Modifier.padding(9.dp).size(24.dp),tint=if(selected)MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface)
                }
                Text(stringResource(option.label),style=MaterialTheme.typography.labelMedium,maxLines=1)
            }
            if(selected)Icon(Lucide.Check,null,Modifier.align(Alignment.TopEnd).size(16.dp),tint=MaterialTheme.colorScheme.secondary)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (previousOrientation != null) activity.requestedOrientation = previousOrientation }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                IconButton(onBack) { Icon(Lucide.ArrowLeft, stringResource(R.string.back)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(camera?.name ?: stringResource(R.string.live), style=MaterialTheme.typography.titleLarge,fontWeight = FontWeight.SemiBold)
                Text(if (camera?.setupStatus == "ready") stringResource(R.string.stream_private) else stringResource(R.string.camera_setup_saved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            camera?.takeIf { it.setupStatus=="ready" }?.let {
                Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.secondary,CircleShape));Spacer(Modifier.width(6.dp));Text(stringResource(R.string.live),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
                    }
                }
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
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val snapshotLoading by vm.snapshotLoading.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    var muted by remember { mutableStateOf(false) }
    var showSnapshot by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    val player = remember {
        val factory = OkHttpDataSource.Factory(vm.httpClient()).setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${vm.token()}"))
        ExoPlayer.Builder(context).setMediaSourceFactory(HlsMediaSource.Factory(factory)).build().apply {
            setMediaItem(MediaItem.fromUri(vm.liveUrl()))
            prepare()
            playWhenReady = true
        }
    }
    LaunchedEffect(muted) { player.volume = if(muted) 0f else 1f }
    DisposableEffect(player) {
        onDispose {
            player.release()
            vm.stop()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.fillMaxWidth().aspectRatio(16/9f),RoundedCornerShape(22.dp),color=ColorTokens.BrandTile,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=7.dp) {
            LivePlayer(player,Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            if(camera.capabilities.audio) CameraAction(if(muted)Lucide.VolumeX else Lucide.Volume2,if(muted)stringResource(R.string.unmute) else stringResource(R.string.mute),{muted=!muted},Modifier.weight(1f))
            if(camera.capabilities.snapshot) CameraAction(Lucide.Aperture,stringResource(R.string.snapshot),{showSnapshot=true;vm.captureSnapshot()},Modifier.weight(1f))
            if(camera.capabilities.presets) CameraAction(Lucide.MapPin,stringResource(R.string.presets),{showPresets=true;vm.loadPresets()},Modifier.weight(1f))
        }
        if(camera.capabilities.ptz) {
            Surface(Modifier.fillMaxWidth(),RoundedCornerShape(24.dp),MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=5.dp) {
                Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(stringResource(R.string.camera_position),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text(stringResource(R.string.press_and_hold),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Lucide.Move,null,tint=MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    PTZPad(vm)
                    if(camera.capabilities.zoom) {
                        HorizontalDivider(Modifier.padding(vertical=14.dp),color=MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.zoom),Modifier.weight(1f),fontWeight=FontWeight.Medium)
                            PTZButton(Lucide.Minus,stringResource(R.string.zoom_out),{vm.move(0.0,0.0,-.55)},{vm.stop()})
                            PTZButton(Lucide.Plus,stringResource(R.string.zoom_in),{vm.move(0.0,0.0,.55)},{vm.stop()})
                        }
                    }
                }
            }
        } else {
            Surface(Modifier.fillMaxWidth(),RoundedCornerShape(18.dp),MaterialTheme.colorScheme.surfaceVariant) { Text(stringResource(R.string.fixed_camera),Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(18.dp))
    }
    if(showSnapshot) com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(title=stringResource(R.string.snapshot),onDismiss={showSnapshot=false}) {
        Box(Modifier.fillMaxWidth().aspectRatio(16/9f).background(ColorTokens.BrandTile,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center) {
            when { snapshotLoading->CircularProgressIndicator();snapshot!=null->Image(requireNotNull(snapshot).asImageBitmap(),camera.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop);else->Text(stringResource(R.string.snapshot_unavailable),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if(showPresets) com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(title=stringResource(R.string.presets),onDismiss={showPresets=false}) {
        if(presets.isEmpty()) Text(stringResource(R.string.no_presets),Modifier.fillMaxWidth().padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { presets.forEach { preset->Surface(onClick={vm.goToPreset(preset.token);showPresets=false},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Lucide.MapPin,null,tint=MaterialTheme.colorScheme.secondary);Spacer(Modifier.width(12.dp));Text(preset.name,fontWeight=FontWeight.Medium)}} } }
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
@Composable private fun CameraAction(icon:ImageVector,label:String,onClick:()->Unit,modifier:Modifier=Modifier){Surface(onClick=onClick,modifier=modifier.height(66.dp),shape=RoundedCornerShape(17.dp),color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=3.dp){Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(icon,label,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.secondary);Spacer(Modifier.height(5.dp));Text(label,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Medium,maxLines=1)}}}
@Composable private fun PTZPad(vm:CameraLiveViewModel){Surface(Modifier.size(190.dp),CircleShape,color=MaterialTheme.colorScheme.surfaceVariant,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),tonalElevation=1.dp){Box(Modifier.fillMaxSize().padding(10.dp)){Surface(Modifier.size(72.dp).align(Alignment.Center),CircleShape,color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Box(contentAlignment=Alignment.Center){Icon(Lucide.Video,null,tint=MaterialTheme.colorScheme.secondary)}};PTZButton(Lucide.ChevronUp,stringResource(R.string.move_up),{vm.move(0.0,.65)},{vm.stop()},Modifier.align(Alignment.TopCenter));PTZButton(Lucide.ChevronLeft,stringResource(R.string.move_left),{vm.move(-.65,0.0)},{vm.stop()},Modifier.align(Alignment.CenterStart));PTZButton(Lucide.ChevronRight,stringResource(R.string.move_right),{vm.move(.65,0.0)},{vm.stop()},Modifier.align(Alignment.CenterEnd));PTZButton(Lucide.ChevronDown,stringResource(R.string.move_down),{vm.move(0.0,-.65)},{vm.stop()},Modifier.align(Alignment.BottomCenter))}}}
@Composable private fun PTZButton(icon:ImageVector,label:String,onPress:()->Unit,onRelease:()->Unit,modifier:Modifier=Modifier){Surface(modifier.size(48.dp).pointerInput(Unit){detectTapGestures(onPress={onPress();tryAwaitRelease();onRelease()})},shape=CircleShape,color=MaterialTheme.colorScheme.surface,shadowElevation=3.dp){Box(contentAlignment=Alignment.Center){Icon(icon,label,Modifier.size(22.dp))}}}
