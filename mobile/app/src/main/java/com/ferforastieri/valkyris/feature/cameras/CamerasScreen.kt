package com.ferforastieri.valkyris.feature.cameras

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.SignalLine
import com.ferforastieri.valkyris.core.design.ColorTokens
import com.ferforastieri.valkyris.core.design.cameraIcon
import com.ferforastieri.valkyris.core.media.WhepLiveController
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.feature.rules.CameraRulesSection
import com.composables.icons.lucide.Aperture
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Move
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.VideoOff
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import org.webrtc.SurfaceViewRenderer

@Composable fun CamerasScreen(onCamera:(String)->Unit,vm:CamerasViewModel=hiltViewModel()){
    LifecycleResumeEffect(Unit) { vm.refresh();onPauseOrDispose {} }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember{mutableStateOf(false)}
    var failedCameraId by remember{mutableStateOf<String?>(null)}
    var editingCameraId by remember{mutableStateOf<String?>(null)}
    var deletingCameraId by remember{mutableStateOf<String?>(null)}
    val failedCamera=state.cameras.firstOrNull{it.id==failedCameraId}
    val editingCamera=state.cameras.firstOrNull{it.id==editingCameraId}
    CamerasContent(state, onCamera={id->
        val camera=state.cameras.firstOrNull{it.id==id}
        if(camera?.setupStatus=="failed")failedCameraId=id else onCamera(id)
    }, onEdit={editingCameraId=it}, onDelete={deletingCameraId=it}, onAdd = { if (!state.creating) showAdd = true })
    if(showAdd)CameraEditorDialog(onDismiss={showAdd=false},onSave={vm.add(it);showAdd=false})
    editingCamera?.let { camera ->
        CameraEditorDialog(
            camera = camera,
            saving = camera.id in state.updating,
            onDismiss = { if (camera.id !in state.updating) editingCameraId = null },
            onSave = { input -> vm.update(camera.id, input); editingCameraId = null },
        )
    }
    failedCamera?.let{camera->CameraFailureSheet(camera,onDismiss={failedCameraId=null},onEdit={editingCameraId=camera.id;failedCameraId=null},onDelete={vm.delete(camera.id);failedCameraId=null})}
    deletingCameraId?.let { id -> state.cameras.firstOrNull { it.id == id }?.let { camera ->
        AlertDialog(
            onDismissRequest = { if (id !in state.deleting) deletingCameraId = null },
            title = { Text("Remover câmera?") },
            text = { Text("A câmera “${camera.name}” e suas regras serão removidas permanentemente.") },
            dismissButton = { TextButton(onClick = { deletingCameraId = null }, enabled = id !in state.deleting) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { Button(onClick = { vm.delete(id); deletingCameraId = null }, enabled = id !in state.deleting, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(stringResource(R.string.remove_camera)) } },
        )
    } }
}

@Composable
fun CamerasContent(state: CamerasState, onCamera: (String) -> Unit = {}, onEdit: (String) -> Unit = {}, onDelete: (String) -> Unit = {}, onAdd: () -> Unit = {}) {
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            Spacer(Modifier.height(10.dp))
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(state.creating){Spacer(Modifier.height(10.dp));LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.validating_camera),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.height(10.dp))
            when{
                state.loading->Box(Modifier.fillMaxSize()){CircularProgressIndicator(Modifier.align(Alignment.Center))}
                state.cameras.isEmpty()->EmptyCameras()
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=96.dp)){items(state.cameras,key={it.id}){camera->CameraCard(camera,state.snapshots[camera.id],onClick={onCamera(camera.id)},onEdit={onEdit(camera.id)},onDelete={onDelete(camera.id)})}}
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
fun CameraFailureSheet(camera:Camera,onDismiss:()->Unit,onEdit:(()->Unit)?=null,onDelete:(()->Unit)?=null){
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title=stringResource(R.string.camera_error_title),
        onDismiss=onDismiss,
        actions={
            onEdit?.let { TextButton(onClick=it) { Icon(Lucide.Pencil, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.edit_camera)) } }
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

@Composable private fun CameraEditorDialog(camera:Camera?=null,saving:Boolean=false,onDismiss:()->Unit,onSave:(CreateCameraRequest)->Unit){
    var name by remember(camera?.id){mutableStateOf(camera?.name.orEmpty())};var icon by remember(camera?.id){mutableStateOf(camera?.icon?:"camera")};var host by remember(camera?.id){mutableStateOf(camera?.host.orEmpty())};var port by remember(camera?.id){mutableStateOf((camera?.port?:2020).toString())};var username by remember(camera?.id){mutableStateOf("")};var password by remember(camera?.id){mutableStateOf("")}
    val editing=camera!=null
    com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet(
        title = stringResource(if(editing) R.string.edit_camera else R.string.add_camera),
        onDismiss = onDismiss,
        dismissEnabled = !saving,
        actions = {
            TextButton(onClick=onDismiss,enabled=!saving) { Text(stringResource(R.string.cancel)) }
            Button(onClick={onSave(CreateCameraRequest(name=name,icon=icon,host=host,port=port.toIntOrNull()?:2020,username=username,password=password))},enabled=!saving&&name.isNotBlank()&&host.isNotBlank()&&(editing || (username.isNotBlank()&&password.isNotBlank()))){
                if(saving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary) else Text(stringResource(R.string.save))
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_name))})
            Text(stringResource(R.string.camera_icon),style=MaterialTheme.typography.labelLarge)
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                cameraIconOptions.chunked(4).forEach { row ->
                    com.ferforastieri.valkyris.core.design.AdaptiveRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        row.forEach { option -> CameraIconChoice(option,icon==option.value,{icon=option.value},Modifier.weight(1f)) }
                        repeat(4-row.size){Spacer(Modifier.weight(1f))}
                    }
                }
            }
            OutlinedTextField(host,{host=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_ip))})
            OutlinedTextField(port,{port=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.onvif_port))})
            OutlinedTextField(username,{username=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.camera_user))})
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(stringResource(if(editing) R.string.camera_password_optional else R.string.camera_password))},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Text(stringResource(if(editing) R.string.camera_edit_credentials_hint else R.string.rtsp_automatic_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraCard(camera: Camera, snapshot: android.graphics.Bitmap?, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
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
                        Text(if (ready) stringResource(R.string.live) else setupLabel(camera), style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else LocalContentColor.current)
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
                IconButton(onClick=onEdit){Icon(Lucide.Pencil,stringResource(R.string.edit_camera),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                IconButton(onClick=onDelete){Icon(Lucide.Trash2,stringResource(R.string.remove_camera),tint=MaterialTheme.colorScheme.error)}
            }
        }
    }
}

private data class CameraIconOption(val value:String,val label:Int,val image:ImageVector)

@Composable
private fun CameraIconChoice(option:CameraIconOption,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier){
    Surface(
        onClick=onClick,
        modifier=modifier.height(64.dp),
        shape=MaterialTheme.shapes.medium,
        color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border=androidx.compose.foundation.BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
    ){
        Box(Modifier.fillMaxSize().padding(horizontal=4.dp,vertical=6.dp)){
            Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                Surface(shape=CircleShape,color=if(selected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface){
                    Icon(option.image,null,Modifier.padding(3.dp).size(22.dp),tint=if(selected)MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface)
                }
                Text(stringResource(option.label),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
fun CameraLiveScreen(cameraId:String,vm:CameraLiveViewModel=hiltViewModel()) {
    val camera by vm.camera.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (previousOrientation != null) activity.requestedOrientation = previousOrientation }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
    val snapshotLoading by vm.snapshotLoading.collectAsStateWithLifecycle()
    val recordingLoading by vm.recordingLoading.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var muted by remember { mutableStateOf(false) }
    var showFullscreen by rememberSaveable { mutableStateOf(false) }
    var renderedFirstFrame by remember { mutableStateOf(false) }
    var streamFailure by remember { mutableStateOf<String?>(null) }
    var pendingMediaAction by remember { mutableStateOf<MediaAction?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMediaAction
        pendingMediaAction = null
        if (granted && action != null) {
            if (action == MediaAction.Snapshot) vm.captureSnapshot() else vm.recordRecentClip()
        } else if (!granted) vm.storagePermissionDenied()
    }
    fun runMediaAction(action: MediaAction) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingMediaAction = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (action == MediaAction.Snapshot) vm.captureSnapshot() else vm.recordRecentClip()
    }
    val controller = remember(vm.whepUrl()) {
        WhepLiveController(
            context = context,
            http = vm.httpClient(),
            endpoint = vm.whepUrl(),
            token = vm.token(),
            onFirstFrame = { renderedFirstFrame = true; streamFailure = null },
            onFailure = { streamFailure = it },
        )
    }
    LaunchedEffect(muted) { controller.setMuted(muted) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.close() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.fillMaxWidth().aspectRatio(16/9f),RoundedCornerShape(22.dp),color=ColorTokens.BrandTile,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=7.dp) {
            Box {
                if (!showFullscreen) ZoomableLivePlayer(controller,preview,renderedFirstFrame,{showFullscreen=true},Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(Color.Black))
                streamFailure?.let { message ->
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .94f),
                    ) {
                        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Box(Modifier.fillMaxSize(),contentAlignment = Alignment.TopEnd) {
                    Surface(onClick={showFullscreen=true},modifier=Modifier.padding(10.dp), shape=CircleShape, color = Color.Black.copy(alpha = .48f)) {
                        Icon(Lucide.Maximize2, stringResource(R.string.fullscreen_camera), Modifier.padding(8.dp).size(18.dp), tint = Color.White)
                    }
                }
            }
        }
        val actions = mutableListOf<CameraActionItem>()
        if(camera.capabilities.audio) actions += CameraActionItem(if(muted)Lucide.VolumeX else Lucide.Volume2,if(muted)stringResource(R.string.unmute) else stringResource(R.string.mute),{muted=!muted})
        actions += CameraActionItem(Lucide.Aperture,stringResource(R.string.snapshot),{runMediaAction(MediaAction.Snapshot)},snapshotLoading)
        actions += CameraActionItem(Lucide.Video,stringResource(R.string.record_recent),{runMediaAction(MediaAction.Recording)},recordingLoading)
        com.ferforastieri.valkyris.core.design.AdaptiveRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            actions.forEach { action -> CameraAction(action.icon,action.label,action.onClick,Modifier.weight(1f),action.loading) }
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
        CameraRulesSection(camera.id)
        Spacer(Modifier.height(18.dp))
    }
    if(showFullscreen) FullscreenLivePlayer(controller,preview,renderedFirstFrame,camera.name,onDismiss={showFullscreen=false})
}

@Composable
private fun LivePlayer(controller: WhepLiveController, modifier: Modifier) {
    val aspectRatio by controller.videoAspectRatio.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val videoModifier = if (maxWidth / maxHeight > aspectRatio) {
            Modifier.height(maxHeight).width(maxHeight * aspectRatio)
        } else {
            Modifier.width(maxWidth).height(maxWidth / aspectRatio)
        }
        AndroidView(
            factory = { context -> SurfaceViewRenderer(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                controller.bind(this)
            } },
            update = { controller.bind(it) },
            onRelease = { controller.unbind(it) },
            modifier = videoModifier,
        )
    }
}

@Composable
private fun ZoomableLivePlayer(controller:WhepLiveController,preview:android.graphics.Bitmap?,rendered:Boolean,onOpen:()->Unit,modifier:Modifier=Modifier){
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.pointerInput(Unit){
        detectTransformGestures { _,pan,zoom,_->
            scale=(scale*zoom).coerceIn(1f,4f)
            offset=if(scale==1f)Offset.Zero else offset+pan
        }
    }.pointerInput(Unit){detectTapGestures(onTap={onOpen()},onDoubleTap={scale=1f;offset=Offset.Zero})}){
        Box(Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y}){
            LivePlayer(controller,Modifier.fillMaxSize())
            if(!rendered)preview?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
        }
        if(!rendered&&preview==null)CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),strokeWidth=2.dp,color=Color.White)
    }
}

@Composable
private fun FullscreenLivePlayer(controller: WhepLiveController,preview:android.graphics.Bitmap?,rendered:Boolean,cameraName: String,onDismiss: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val activity=remember(context){context.findActivity()}
    DisposableEffect(activity){
        val previous=activity?.requestedOrientation
        activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose{if(previous!=null)activity.requestedOrientation=previous}
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var scale by remember(configuration.screenWidthDp, configuration.screenHeightDp) { mutableFloatStateOf(1f) }
    var offset by remember(configuration.screenWidthDp, configuration.screenHeightDp) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap={scale=1f;offset=Offset.Zero})
                },
            ) {
                LivePlayer(controller, Modifier.fillMaxSize())
                if(!rendered)preview?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
                if(!rendered&&preview==null)CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp),strokeWidth=2.dp,color=Color.White)
            }
            Text(
                stringResource(R.string.pinch_to_zoom),
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp),
                color = Color.White.copy(alpha = .78f),
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp).background(Color.Black.copy(alpha = .55f), CircleShape),
            ) { Icon(Lucide.X, stringResource(R.string.close_fullscreen), tint = Color.White) }
            Text(cameraName, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(20.dp), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
@Composable private fun CameraAction(icon:ImageVector,label:String,onClick:()->Unit,modifier:Modifier=Modifier,loading:Boolean=false){Surface(onClick=onClick,enabled=!loading,modifier=modifier.heightIn(min=66.dp),shape=RoundedCornerShape(17.dp),color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=3.dp){Column(Modifier.fillMaxWidth().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){if(loading)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(icon,label,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.secondary);Spacer(Modifier.height(5.dp));Text(label,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Medium)}}}
private enum class MediaAction { Snapshot, Recording }
private data class CameraActionItem(val icon:ImageVector,val label:String,val onClick:()->Unit,val loading:Boolean=false)
@Composable private fun PTZPad(vm:CameraLiveViewModel){Surface(Modifier.size(190.dp),CircleShape,color=MaterialTheme.colorScheme.surfaceVariant,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),tonalElevation=1.dp){Box(Modifier.fillMaxSize().padding(10.dp)){Surface(Modifier.size(72.dp).align(Alignment.Center),CircleShape,color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Box(contentAlignment=Alignment.Center){Icon(Lucide.Video,null,tint=MaterialTheme.colorScheme.secondary)}};PTZButton(Lucide.ChevronUp,stringResource(R.string.move_up),{vm.move(0.0,.65)},{vm.stop()},Modifier.align(Alignment.TopCenter));PTZButton(Lucide.ChevronLeft,stringResource(R.string.move_left),{vm.move(-.65,0.0)},{vm.stop()},Modifier.align(Alignment.CenterStart));PTZButton(Lucide.ChevronRight,stringResource(R.string.move_right),{vm.move(.65,0.0)},{vm.stop()},Modifier.align(Alignment.CenterEnd));PTZButton(Lucide.ChevronDown,stringResource(R.string.move_down),{vm.move(0.0,-.65)},{vm.stop()},Modifier.align(Alignment.BottomCenter))}}}
@Composable private fun PTZButton(icon:ImageVector,label:String,onPress:()->Unit,onRelease:()->Unit,modifier:Modifier=Modifier){
    var pressed by remember{mutableStateOf(false)}
    val scale by animateFloatAsState(if(pressed).88f else 1f,label="ptz-scale")
    val color by animateColorAsState(if(pressed)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,label="ptz-color")
    Surface(
        modifier.size(48.dp).graphicsLayer{scaleX=scale;scaleY=scale}.pointerInput(Unit){detectTapGestures(onPress={pressed=true;onPress();try{tryAwaitRelease()}finally{onRelease();pressed=false}})},
        shape=CircleShape,color=color,shadowElevation=if(pressed)0.dp else 3.dp,
    ){Box(contentAlignment=Alignment.Center){Icon(icon,label,Modifier.size(22.dp),tint=if(pressed)MaterialTheme.colorScheme.secondary else LocalContentColor.current)}}
}
