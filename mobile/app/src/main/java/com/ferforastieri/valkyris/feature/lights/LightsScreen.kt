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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
    // Guardamos só o identificador: a luz viva vem do estado, então a folha
    // acompanha o aparelho sem ser recriada a cada atualização.
    var selectedId by rememberSaveable{mutableStateOf<String?>(null)}
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
                    items(state.lights,key={it.id}){light->LightCard(light,light.id in state.busy,{vm.control(light.id,LightStatePatch(power=!light.state.power))},{selectedId=light.id},if(admin){{removing=light}}else null,{vm.toggleFavorite(light.id,!light.favorite)},if(admin){{renaming=light}}else null)}
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
    state.lights.firstOrNull{it.id==selectedId}?.let{light->
        LightControls(light,light.id in state.busy,{selectedId=null}){vm.control(light.id,it)}
    }
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

/**
 * Card da lâmpada no mesmo padrão dos outros dispositivos: ícone à esquerda,
 * nome e situação no meio, ações à direita. Tocar no card abre os ajustes;
 * tocar na lâmpada (o ícone) liga ou desliga.
 */
@Composable private fun LightCard(light:LightDevice,busy:Boolean,onPower:()->Unit,onOpen:()->Unit,onRemove:(()->Unit)?,onFavorite:(()->Unit)?=null,onEdit:(()->Unit)?=null){
    val colorMode=light.state.mode=="color"
    val aura=if(colorMode)Color.hsv(light.state.color.hue.toFloat(),light.state.color.saturation/100f,(light.state.color.value/100f).coerceAtLeast(.4f))else warmWhite(light.state.temperatureKelvin)
    val on=light.state.power
    val lit=on&&light.state.online
    val pulse=rememberInfiniteTransition(label="lamp")
    val glow by pulse.animateFloat(initialValue=.78f,targetValue=1f,animationSpec=infiniteRepeatable(tween(1400),RepeatMode.Reverse),label="glow")
    Card(onClick=onOpen,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,if(light.state.online&&on)aura.copy(alpha=.6f)else MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(
                onClick=onPower,
                enabled=light.state.online,
                shape=CircleShape,
                color=if(on)aura.copy(alpha=.22f*glow)else MaterialTheme.colorScheme.secondaryContainer,
            ){Icon(
                if(on)Lucide.Lightbulb else Lucide.LightbulbOff,
                stringResource(if(on)R.string.favorite_light_on else R.string.favorite_light_off),
                Modifier.padding(12.dp).size(24.dp).alpha(if(lit)glow else 1f).scale(if(lit)(.96f+.06f*glow)else 1f),
                tint=if(on)aura else MaterialTheme.colorScheme.onSecondaryContainer,
            )}
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)){
                Text(light.name,fontWeight=FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        light.room.takeIf(String::isNotBlank),
                        when{
                            light.setupStatus=="failed"->light.setupError.ifBlank{stringResource(R.string.light_setup_failed)}
                            light.setupStatus!="ready"->stringResource(R.string.light_setup_pending)
                            light.state.online->"${stringResource(if(on)R.string.favorite_light_on else R.string.favorite_light_off)} · ${light.state.brightness}%"
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
            onRemove?.let{IconButton(onClick=it){Icon(Lucide.Trash2,stringResource(R.string.remove),tint=MaterialTheme.colorScheme.error)}}
            if(busy)CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
        }
    }
}

/** Cores pré-definidas para toque rápido no modo cor. */
private val presetHues = listOf(0, 30, 55, 120, 180, 220, 275, 320)

/**
 * Folha de ajustes da lâmpada: prévia na cor atual, um controle de liga/desliga
 * claro (não um switch) e os ajustes de intensidade, branco e cor. Os valores
 * ficam presos ao identificador da lâmpada, então a folha não "recarrega" a cada
 * toque.
 */
@Composable private fun LightControls(light:LightDevice,busy:Boolean,onDismiss:()->Unit,onChange:(LightStatePatch)->Unit){
    var brightness by remember(light.id){mutableFloatStateOf(light.state.brightness.coerceIn(1,100).toFloat())}
    var temperature by remember(light.id){mutableFloatStateOf(light.state.temperatureKelvin.coerceIn(2700,6500).toFloat())}
    var hue by remember(light.id){mutableFloatStateOf(light.state.color.hue.coerceIn(0,360).toFloat())}
    var saturation by remember(light.id){mutableFloatStateOf(light.state.color.saturation.coerceIn(0,100).toFloat())}
    val on=light.state.power
    val reachable=light.state.online&&!busy
    val preview=if(light.state.mode=="color")Color.hsv(hue,saturation/100f,(brightness/100f).coerceAtLeast(.12f))else warmWhite(temperature.toInt())
    ValkyrisBottomSheet(title=light.name,onDismiss=onDismiss,dismissEnabled=!busy,actions={TextButton(onClick=onDismiss){Text(stringResource(R.string.close))}}){
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
                Surface(
                    Modifier.size(132.dp),
                    CircleShape,
                    color=if(on)preview else MaterialTheme.colorScheme.surfaceVariant,
                    border=BorderStroke(2.dp,MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation=if(on)8.dp else 0.dp,
                ){Icon(if(on)Lucide.Lightbulb else Lucide.LightbulbOff,null,Modifier.padding(38.dp),tint=if(on)onColorFor(preview) else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            light.setupError.takeIf{it.isNotBlank()&&light.setupStatus=="failed"}?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            // Liga/desliga explícito: um botão grande que muda de cor, no lugar do switch.
            Surface(
                onClick={onChange(LightStatePatch(power=!on))},
                enabled=reachable,
                modifier=Modifier.fillMaxWidth(),
                shape=MaterialTheme.shapes.large,
                color=if(on)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border=BorderStroke(1.dp,if(on)MaterialTheme.colorScheme.secondary.copy(alpha=.5f)else MaterialTheme.colorScheme.outlineVariant),
            ){
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                    Icon(Lucide.Power,null,Modifier.size(22.dp),tint=if(on)MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(if(on)R.string.favorite_light_on else R.string.favorite_light_off),Modifier.weight(1f),fontWeight=FontWeight.SemiBold,color=if(on)MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(reachable)Text(stringResource(if(on)R.string.light_turn_off else R.string.light_turn_on),style=MaterialTheme.typography.labelLarge,color=if(on)MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(!light.state.online)Text(stringResource(R.string.unavailable),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(light.capabilities.brightness){
                LightSlider(Lucide.Sun,stringResource(R.string.brightness_value,brightness.toInt()),brightness,1f..100f,reachable,{brightness=it}){onChange(LightStatePatch(brightness=brightness.toInt()))}
            }
            if(light.capabilities.colorTemperature){
                LightSlider(Lucide.ThermometerSun,stringResource(R.string.white_temperature,temperature.toInt()),temperature,2700f..6500f,reachable,{temperature=it}){onChange(LightStatePatch(temperatureKelvin=temperature.toInt()))}
            }
            if(light.capabilities.color){
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Icon(Lucide.Palette,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.color),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        presetHues.forEach{ preset ->
                            val chosen= hue.toInt()==preset && saturation>=90f
                            Surface(
                                onClick={hue=preset.toFloat();saturation=100f;onChange(LightStatePatch(color=LightColor(preset,100,brightness.toInt())))},
                                enabled=reachable,
                                modifier=Modifier.weight(1f).aspectRatio(1f),
                                shape=CircleShape,
                                color=Color.hsv(preset.toFloat(),1f,(brightness/100f).coerceAtLeast(.35f)),
                                border=BorderStroke(if(chosen)2.dp else 0.dp,if(chosen)MaterialTheme.colorScheme.onSurface else Color.Transparent),
                            ){}
                        }
                    }
                    LightSlider(Lucide.Palette,stringResource(R.string.hue_value,hue.toInt()),hue,0f..360f,reachable,{hue=it}){onChange(LightStatePatch(color=LightColor(hue.toInt(),saturation.toInt(),brightness.toInt())))}
                }
            }
        }
    }
}

@Composable private fun LightSlider(icon:ImageVector,label:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,onValue:(Float)->Unit,onFinished:()->Unit){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Icon(icon,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value,onValue,valueRange=range,enabled=enabled,onValueChangeFinished=onFinished)
    }
}

/** Branco: interpola entre âmbar quente e branco frio. */
private fun warmWhite(kelvin:Int):Color{
    val t=((kelvin-2700).coerceIn(0,3800))/3800f
    return Color(
        red=(0xFF/255f)+(0xDC/255f-0xFF/255f)*t,
        green=(0xD7/255f)+(0xE9/255f-0xD7/255f)*t,
        blue=(0xA0/255f)+(0xFF/255f-0xA0/255f)*t,
    )
}

/** Contraste do ícone sobre a cor da lâmpada. */
private fun onColorFor(background:Color)=if(background.luminance()>0.55f)Color(0xFF1B1B1F)else Color.White

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
