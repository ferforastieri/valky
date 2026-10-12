package com.ferforastieri.valkyris.feature.lights

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    // Entering an empty lighting screen already looks for bulbs on the network.
    LaunchedEffect(admin,state.loading,state.lights.size){if(admin&&!state.loading&&state.lights.isEmpty())vm.discover()}
    var selected by remember{mutableStateOf<LightDevice?>(null)}
    var removing by remember{mutableStateOf<LightDevice?>(null)}
    var editing by remember{mutableStateOf<LightDevice?>(null)}
    var adding by remember{mutableStateOf(false)}
    var candidate by remember{mutableStateOf<LightCandidate?>(null)}
    val registered=state.lights
    val discovered=unregisteredCandidates(state.discovered,registered)
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            state.error?.let{ /* errors surface as a toast */ }
            if(admin)LightDiscoveryHeader(
                discovering=state.discovering,
                found=discovered.size,
                onSearch={vm.discover()},
                modifier=Modifier.padding(top=10.dp),
            )
            when{
                state.loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                state.lights.isEmpty()&&discovered.isEmpty()&&!state.discovering->EmptyLighting(admin)
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(start=0.dp,top=12.dp,end=0.dp,bottom=96.dp)){
                    if(discovered.isNotEmpty()){
                        item{Text(stringResource(R.string.light_discovered_title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
                        items(discovered,key={"found-${it.address}"}){found->DiscoveredLightCard(found,onAdd={candidate=found;adding=true})}
                        item{HorizontalDivider()}
                    }
                    if(state.lights.isNotEmpty())item{Text(stringResource(R.string.light_my_lights),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
                    items(state.lights,key={it.id}){light->LightCard(light,light.id in state.busy,{vm.control(light.id,LightStatePatch(power=!light.state.power))},{selected=light},if(admin){{removing=light}}else null,{vm.toggleFavorite(light.id,!light.favorite)},if(admin){{editing=light}}else null)}
                }
            }
        }
        if(admin)FloatingActionButton(
            onClick={candidate=null;adding=true},
            modifier=Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=18.dp),
            containerColor=MaterialTheme.colorScheme.secondary,
            contentColor=MaterialTheme.colorScheme.onSecondary,
        ){Icon(Lucide.Plus,stringResource(R.string.add_light))}
    }
    selected?.let{light->LightControls(light,light.id in state.busy,{selected=null}){vm.control(light.id,it)}}
    if(adding)LightEditorSheet(
        candidate=candidate,
        saving=state.saving,
        allowManual=!state.discovering,
        onDiscover={vm.discover()},
        onDismiss={adding=false;candidate=null},
        onSave={input->vm.add(input){saved->if(saved){adding=false;candidate=null}}},
    )
    editing?.let{light->LightEditorSheet(
        initial=light,
        saving=state.saving,
        allowManual=true,
        onDiscover={vm.discover()},
        onDismiss={editing=null},
        onSave={input->vm.update(light.id,LightUpdate(name=input.name,room=input.room,localKey=input.localKey.takeIf{it.isNotBlank()},address=input.address.takeIf{it.isNotBlank()})){saved->if(saved)editing=null}},
    )}
    removing?.let{light->AlertDialog(onDismissRequest={removing=null},title={Text(stringResource(R.string.remove_light_title))},text={Text(stringResource(R.string.remove_light_body,light.name))},dismissButton={TextButton(onClick={removing=null}){Text(stringResource(R.string.cancel))}},confirmButton={Button(onClick={vm.delete(light.id){removing=null}},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(stringResource(R.string.remove))}})}
}

@Composable private fun LightDiscoveryHeader(discovering:Boolean,found:Int,onSearch:()->Unit,modifier:Modifier=Modifier){
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Icon(Lucide.Radar,null,Modifier.padding(9.dp).size(18.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)}
            Spacer(Modifier.width(10.dp))
            Text(
                when{
                    discovering->stringResource(R.string.light_searching)
                    found>0->stringResource(R.string.light_found_count,found)
                    else->stringResource(R.string.light_discover_empty)
                },
                Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick=onSearch,enabled=!discovering){Text(stringResource(R.string.light_discover_action))}
        }
        if(discovering)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable private fun DiscoveredLightCard(candidate:LightCandidate,onAdd:()->Unit){
    Surface(
        onClick=onAdd,
        modifier=Modifier.fillMaxWidth(),
        shape=MaterialTheme.shapes.large,
        color=MaterialTheme.colorScheme.secondaryContainer,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.secondary.copy(alpha=.35f)),
        shadowElevation=2.dp,
    ){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondary){Icon(Lucide.Lightbulb,null,Modifier.padding(10.dp).size(22.dp),tint=MaterialTheme.colorScheme.onSecondary)}
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)){
                Text(candidate.deviceId.ifBlank{stringResource(R.string.light_unknown_device)},fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    listOfNotNull(
                        candidate.address,
                        candidate.version.takeIf{it>0}?.let{"v%.1f".format(it)},
                        candidate.mac.takeIf(String::isNotBlank),
                    ).joinToString(" · "),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Icon(Lucide.Link,stringResource(R.string.light_add),Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable private fun EmptyLighting(admin:Boolean){
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=3.dp){
            Column(Modifier.padding(horizontal=26.dp,vertical=24.dp).widthIn(max=340.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Icon(Lucide.Lightbulb,null,Modifier.padding(13.dp).size(28.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)}
                Text(stringResource(R.string.no_lights),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
                Text(stringResource(if(admin)R.string.no_lights_body_admin else R.string.no_lights_body),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun LightCard(light:LightDevice,busy:Boolean,onPower:()->Unit,onOpen:()->Unit,onRemove:(()->Unit)?,onFavorite:(()->Unit)?=null,onEdit:(()->Unit)?=null){
    val aura=if(light.state.mode=="color")Color.hsv(light.state.color.hue.toFloat(),light.state.color.saturation/100f,light.state.color.value/100f)else Color(0xFFFFD7A0)
    Card(onClick=onOpen,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,if(light.state.online)aura.copy(alpha=.7f)else MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().background(aura.copy(alpha=if(light.state.power).12f else .03f)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=aura.copy(alpha=if(light.state.power).28f else .08f)){Icon(if(light.state.power)Lucide.Lightbulb else Lucide.LightbulbOff,null,Modifier.padding(12.dp).size(24.dp),tint=if(light.state.power)aura else MaterialTheme.colorScheme.onSurfaceVariant)}
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
            if(busy)CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=2.dp)else Switch(checked=light.state.power,onCheckedChange={onPower()},enabled=light.state.online)
        }
    }
}

@Composable private fun LightControls(light:LightDevice,busy:Boolean,onDismiss:()->Unit,onChange:(LightStatePatch)->Unit){
    var brightness by remember(light.id,light.state.brightness){mutableFloatStateOf(light.state.brightness.coerceAtLeast(1).toFloat())};var temperature by remember(light.id,light.state.temperatureKelvin){mutableFloatStateOf(light.state.temperatureKelvin.toFloat())};var hue by remember(light.id,light.state.color.hue){mutableFloatStateOf(light.state.color.hue.toFloat())};var saturation by remember(light.id,light.state.color.saturation){mutableFloatStateOf(light.state.color.saturation.toFloat())};val preview=Color.hsv(hue,saturation/100f,brightness/100f)
    ValkyrisBottomSheet(title=light.name,onDismiss=onDismiss,dismissEnabled=!busy,actions={TextButton(onClick=onDismiss){Text(stringResource(R.string.close))}}){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
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

@Composable private fun LightEditorSheet(
    candidate:LightCandidate?=null,
    initial:LightDevice?=null,
    saving:Boolean,
    allowManual:Boolean,
    onDiscover:()->Unit,
    onDismiss:()->Unit,
    onSave:(LightInput)->Unit,
){
    var name by remember(initial?.id){mutableStateOf(initial?.name.orEmpty())}
    var room by remember(initial?.id){mutableStateOf(initial?.room.orEmpty())}
    var deviceId by remember(initial?.id,candidate?.deviceId){mutableStateOf(initial?.deviceId.orEmpty().ifBlank{candidate?.deviceId.orEmpty()})}
    var address by remember(initial?.id,candidate?.address){mutableStateOf(initial?.lastAddress.orEmpty().ifBlank{candidate?.address.orEmpty()})}
    var key by remember(initial?.id){mutableStateOf("")}
    val keyValid=key.isBlank()||lightKeyValid(key)
    val canSave=name.isNotBlank()&&deviceId.trim().isNotBlank()&&keyValid&&(initial!=null||lightKeyValid(key))
    ValkyrisBottomSheet(
        title=stringResource(if(initial==null)R.string.add_light else R.string.light_edit),
        onDismiss=onDismiss,
        dismissEnabled=!saving,
        actions={
            TextButton(onClick=onDismiss,enabled=!saving){Text(stringResource(R.string.cancel))}
            Button(
                onClick={onSave(LightInput(name=name.trim(),room=room.trim(),deviceId=deviceId.trim(),localKey=key.trim(),address=address.trim(),protocolVersion=initial?.protocolVersion?:candidate?.version?:0.0))},
                enabled=!saving&&canSave,
            ){if(saving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary)else Text(stringResource(R.string.save))}
        },
    ){
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(initial==null)Text(stringResource(R.string.light_add_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_name))},singleLine=true)
            OutlinedTextField(room,{room=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_room))},singleLine=true)
            OutlinedTextField(deviceId,{deviceId=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_device_id))},supportingText={Text(stringResource(R.string.light_device_id_hint))},singleLine=true)
            OutlinedTextField(address,{address=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.light_address))},supportingText={Text(stringResource(R.string.light_address_hint))},singleLine=true)
            OutlinedTextField(
                key,{key=it.trim()},Modifier.fillMaxWidth(),
                label={Text(stringResource(if(initial==null)R.string.light_local_key else R.string.light_local_key_replace))},
                supportingText={Text(stringResource(if(keyValid)R.string.light_local_key_hint else R.string.light_local_key_invalid))},
                isError=!keyValid,
                visualTransformation=PasswordVisualTransformation(),
                singleLine=true,
            )
            Row(verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick=onDiscover,enabled=allowManual&&!saving){Icon(Lucide.Radar,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(stringResource(R.string.light_search_again))}
            }
            Text(stringResource(R.string.light_security_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
