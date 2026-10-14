package com.ferforastieri.valkyris.feature.lights

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.*

@Composable fun LightsScreen(admin:Boolean,vm:LightsViewModel=hiltViewModel()){
    LifecycleResumeEffect(Unit){vm.refresh();onPauseOrDispose{}}
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember{mutableStateOf<LightDevice?>(null)}
    var removing by remember{mutableStateOf<LightDevice?>(null)}
    var renaming by remember{mutableStateOf<LightDevice?>(null)}
    var adding by rememberSaveable{mutableStateOf(false)}
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            state.error?.let{ /* errors surface as a toast */ }
            when{
                state.loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                state.lights.isEmpty()->EmptyLighting(admin)
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=12.dp,bottom=96.dp)){
                    items(state.lights,key={it.id}){light->LightCard(light,light.id in state.busy,{vm.control(light.id,LightStatePatch(power=!light.state.power))},{selected=light},if(admin){{removing=light}}else null,{vm.toggleFavorite(light.id,!light.favorite)},if(admin){{renaming=light}}else null)}
                }
            }
        }
        if(admin)FloatingActionButton(
            onClick={adding=true},
            modifier=Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=18.dp),
            containerColor=MaterialTheme.colorScheme.secondary,
            contentColor=MaterialTheme.colorScheme.onSecondary,
        ){Icon(Lucide.Plus,stringResource(R.string.add_light))}
    }
    if(adding)LightRegisterSheet(
        state=state,
        onScan={vm.scanForLights()},
        onSubmit={input,done->vm.register(input,done)},
        onDismiss={adding=false;vm.clearRegistration()},
    )
    selected?.let{light->LightControls(light,light.id in state.busy,{selected=null}){vm.control(light.id,it)}}
    renaming?.let{light->LightNameSheet(light,{renaming=null}){name,room->vm.rename(light.id,name,room){renaming=null}}}
    removing?.let{light->AlertDialog(onDismissRequest={removing=null},title={Text(stringResource(R.string.remove_light_title))},text={Text(stringResource(R.string.remove_light_body,light.name))},dismissButton={TextButton(onClick={removing=null}){Text(stringResource(R.string.cancel))}},confirmButton={Button(onClick={vm.delete(light.id){removing=null}},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(stringResource(R.string.remove))}})}
}

@Composable private fun EmptyLighting(admin:Boolean){
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=3.dp){
            Column(Modifier.padding(horizontal=26.dp,vertical=24.dp).widthIn(max=340.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Icon(Lucide.Lightbulb,null,Modifier.padding(13.dp).size(28.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)}
                Text(stringResource(R.string.no_lights),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
                Text(stringResource(if(admin)R.string.no_lights_body_admin else R.string.no_lights_body),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable private fun LightCard(light:LightDevice,busy:Boolean,onPower:()->Unit,onOpen:()->Unit,onRemove:(()->Unit)?,onFavorite:(()->Unit)?=null,onEdit:(()->Unit)?=null){
    val aura=if(light.state.mode=="color")Color.hsv(light.state.color.hue.toFloat(),light.state.color.saturation/100f,light.state.color.value/100f)else Color(0xFFFFD7A0)
    // Acesa, a lâmpada respira: o brilho do ícone pulsa na cor atual.
    val lit=light.state.power&&light.state.online
    val pulse=rememberInfiniteTransition(label="lamp")
    val glow by pulse.animateFloat(initialValue=.62f,targetValue=1f,animationSpec=infiniteRepeatable(tween(1400),RepeatMode.Reverse),label="glow")
    // O toque no card liga ou desliga; o botão de ajustes abre cor e intensidade.
    Card(onClick=onPower,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,if(light.state.online)aura.copy(alpha=.7f)else MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().background(aura.copy(alpha=if(light.state.power).12f else .03f)).padding(start=16.dp,top=10.dp,bottom=10.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=aura.copy(alpha=if(light.state.power).28f*glow else .08f)){Icon(if(light.state.power)Lucide.Lightbulb else Lucide.LightbulbOff,null,Modifier.padding(12.dp).size(24.dp).alpha(if(lit)glow else 1f).scale(if(lit)(.96f+.06f*glow)else 1f),tint=if(light.state.power)aura else MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.width(13.dp));Column(Modifier.weight(1f)){
                Text(light.name,fontWeight=FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        light.room.takeIf(String::isNotBlank),
                        when{
                            light.setupStatus=="failed"->light.setupError.ifBlank{stringResource(R.string.light_setup_failed)}
                            light.setupStatus!="ready"->stringResource(R.string.light_setup_pending)
                            light.state.online->"${stringResource(R.string.available)} · ${light.state.brightness}%"
                            else->stringResource(R.string.unavailable)
                        },
                    ).joinToString(" · "),
                    style=MaterialTheme.typography.bodySmall,
                    color=if(light.setupStatus=="failed"||!light.state.online)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines=2,
                )
            }
            onFavorite?.let{IconButton(onClick=it){Icon(Lucide.Star,stringResource(if(light.favorite)R.string.unfavorite else R.string.favorite),tint=if(light.favorite)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)}}
            onEdit?.let{IconButton(onClick=it){Icon(Lucide.Pencil,stringResource(R.string.edit),tint=MaterialTheme.colorScheme.onSurfaceVariant)}}
            onRemove?.let{IconButton(onClick=it){Icon(Lucide.Trash2,stringResource(R.string.remove),tint=MaterialTheme.colorScheme.onSurfaceVariant)}}
            if(busy)CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=2.dp)
            IconButton(onClick=onOpen,enabled=light.state.online){Icon(Lucide.SlidersHorizontal,stringResource(R.string.light_options),tint=if(light.state.online)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)}
        }
    }
}

@Composable private fun LightControls(light:LightDevice,busy:Boolean,onDismiss:()->Unit,onChange:(LightStatePatch)->Unit){
    var brightness by remember(light.id,light.state.brightness){mutableFloatStateOf(light.state.brightness.coerceIn(1,100).toFloat())};var temperature by remember(light.id,light.state.temperatureKelvin){mutableFloatStateOf(light.state.temperatureKelvin.coerceIn(2700,6500).toFloat())};var hue by remember(light.id,light.state.color.hue){mutableFloatStateOf(light.state.color.hue.coerceIn(0,360).toFloat())};var saturation by remember(light.id,light.state.color.saturation){mutableFloatStateOf(light.state.color.saturation.coerceIn(0,100).toFloat())};val preview=Color.hsv(hue,saturation/100f,brightness/100f)
    // A folha já rola sozinha: nada de um segundo verticalScroll aqui (isso
    // derrubava o app ao abrir os ajustes).
    ValkyrisBottomSheet(title=light.name,onDismiss=onDismiss,dismissEnabled=!busy,actions={TextButton(onClick=onDismiss){Text(stringResource(R.string.close))}}){Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(if(light.state.online)R.string.available else R.string.unavailable),Modifier.weight(1f),color=if(light.state.online)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error);Switch(light.state.power,{onChange(LightStatePatch(power=it))},enabled=light.state.online&&!busy)}
        light.setupError.takeIf{it.isNotBlank()&&light.setupStatus=="failed"}?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        if(light.capabilities.brightness){
            Text(stringResource(R.string.brightness_value,brightness.toInt()),fontWeight=FontWeight.SemiBold);Slider(brightness,{brightness=it},valueRange=1f..100f,onValueChangeFinished={onChange(LightStatePatch(brightness=brightness.toInt()))},enabled=light.state.online)
        }
        if(light.capabilities.colorTemperature){
            Text(stringResource(R.string.white_temperature,temperature.toInt()),fontWeight=FontWeight.SemiBold);Slider(temperature,{temperature=it},valueRange=2700f..6500f,onValueChangeFinished={onChange(LightStatePatch(temperatureKelvin=temperature.toInt()))},enabled=light.state.online)
        }
        if(light.capabilities.color){
            HorizontalDivider();Row(verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(44.dp),shape=CircleShape,color=preview){};Spacer(Modifier.width(12.dp));Text(stringResource(R.string.color),fontWeight=FontWeight.SemiBold)}
            Text(stringResource(R.string.hue_value,hue.toInt()));Slider(hue,{hue=it},valueRange=0f..360f,onValueChangeFinished={onChange(LightStatePatch(color=LightColor(hue.toInt(),saturation.toInt(),brightness.toInt())))},enabled=light.state.online)
            Text(stringResource(R.string.saturation_value,saturation.toInt()));Slider(saturation,{saturation=it},valueRange=0f..100f,onValueChangeFinished={onChange(LightStatePatch(color=LightColor(hue.toInt(),saturation.toInt(),brightness.toInt())))},enabled=light.state.online)
        }
    }}
}

/**
 * Cadastro de uma lâmpada já configurada em outro aplicativo (por exemplo o
 * Tuya Smart). O celular informa endereço, identificador e a chave local; a
 * busca na rede acontece no servidor e só ajuda a preencher o formulário.
 */
@Composable private fun LightRegisterSheet(
    state:LightsState,
    onScan:()->Unit,
    onSubmit:(LightRegistration,(Boolean)->Unit)->Unit,
    onDismiss:()->Unit,
){
    val running=state.registering
    var name by rememberSaveable{mutableStateOf("")}
    var room by rememberSaveable{mutableStateOf("")}
    var address by rememberSaveable{mutableStateOf("")}
    var deviceId by rememberSaveable{mutableStateOf("")}
    var localKey by rememberSaveable{mutableStateOf("")}
    val ready=address.isNotBlank()&&deviceId.isNotBlank()&&localKey.trim().length==16&&!running
    ValkyrisBottomSheet(
        title=stringResource(R.string.add_light),
        onDismiss=onDismiss,
        dismissEnabled=!running,
        dismissByOutsideTap=false,
        actions={
            TextButton(onClick=onDismiss,enabled=!running){Text(stringResource(R.string.cancel))}
            Button(
                onClick={onSubmit(LightRegistration(address=address.trim(),deviceId=deviceId.trim(),localKey=localKey.trim(),name=name.trim(),room=room.trim())){ok->if(ok)onDismiss()}},
                enabled=ready,
            ){if(running)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Text(stringResource(R.string.save))}
        },
    ){
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(stringResource(R.string.light_register_intro),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.registrationError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_name))},singleLine=true,enabled=!running)
            OutlinedTextField(room,{room=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_room))},singleLine=true,enabled=!running)
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(stringResource(R.string.light_register_network),Modifier.weight(1f),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick=onScan,enabled=!running&&!state.scanning){
                    if(state.scanning)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)else Text(stringResource(R.string.light_register_scan))
                }
            }
            state.candidates.forEach{candidate->
                Surface(onClick={address=candidate.address;if(candidate.deviceId.isNotBlank())deviceId=candidate.deviceId},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant){
                    Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(Lucide.Wifi,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)){
                            Text(candidate.address,style=MaterialTheme.typography.bodyMedium)
                            if(candidate.deviceId.isNotBlank())Text(candidate.deviceId,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedTextField(address,{address=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_register_address))},singleLine=true,enabled=!running,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
            OutlinedTextField(deviceId,{deviceId=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_register_device_id))},singleLine=true,enabled=!running)
            OutlinedTextField(localKey,{localKey=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_register_key))},singleLine=true,enabled=!running)
            Text(stringResource(R.string.light_register_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LightNameSheet(light:LightDevice,onDismiss:()->Unit,onSave:(String,String)->Unit){
    var name by remember(light.id){mutableStateOf(light.name)}
    var room by remember(light.id){mutableStateOf(light.room)}
    ValkyrisBottomSheet(title=stringResource(R.string.light_edit),onDismiss=onDismiss,actions={
        TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}
        Button(onClick={onSave(name.trim(),room.trim())},enabled=name.isNotBlank()){Text(stringResource(R.string.save))}
    }){
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_name))},singleLine=true)
            OutlinedTextField(room,{room=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_room))},singleLine=true)
        }
    }
}
