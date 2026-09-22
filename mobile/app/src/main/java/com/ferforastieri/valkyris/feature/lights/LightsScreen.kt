package com.ferforastieri.valkyris.feature.lights

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.serialization.json.*

@Composable fun LightsScreen(admin:Boolean,vm:LightsViewModel=hiltViewModel()){
    LifecycleResumeEffect(Unit){vm.refresh();onPauseOrDispose{}}
    val state by vm.state.collectAsStateWithLifecycle();var editor by remember{mutableStateOf<LightDevice?>(null)};var adding by remember{mutableStateOf(false)};var selected by remember{mutableStateOf<LightDevice?>(null)};var removing by remember{mutableStateOf<LightDevice?>(null)}
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            when{
                state.loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                state.lights.isEmpty()->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(Lucide.Lightbulb,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.secondary);Text(stringResource(R.string.no_lights),fontWeight=FontWeight.SemiBold);Text(stringResource(R.string.no_lights_body),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(start=0.dp,top=12.dp,end=0.dp,bottom=96.dp)){items(state.lights,key={it.id}){light->LightCard(light,light.id in state.busy,{vm.control(light.id,LightStatePatch(power=!light.state.power))},{selected=light},if(admin){{editor=light}}else null)}}
            }
        }
        if(admin)FloatingActionButton(onClick={adding=true},modifier=Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=18.dp),containerColor=MaterialTheme.colorScheme.secondary,contentColor=MaterialTheme.colorScheme.onSecondary){Icon(Lucide.Plus,stringResource(R.string.add_light))}
    }
    if(adding)LightEditor(null,state.saving,state.error,{adding=false},{vm.add(it){adding=false}})
    editor?.let{light->LightEditor(light,light.id in state.busy,state.error,{editor=null},{input->vm.update(light.id,UpdateLightRequest(input.name,input.room,input.localKey,input.ip,input.protocolVersion,true)){editor=null}},onRemove={removing=light;editor=null},onTest={vm.test(light.id)})}
    selected?.let{light->LightControls(light,light.id in state.busy,{selected=null}){vm.control(light.id,it)}}
    removing?.let{light->AlertDialog(onDismissRequest={removing=null},title={Text(stringResource(R.string.remove_light_title))},text={Text(stringResource(R.string.remove_light_body,light.name))},dismissButton={TextButton(onClick={removing=null}){Text(stringResource(R.string.cancel))}},confirmButton={Button(onClick={vm.delete(light.id){removing=null}},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(stringResource(R.string.remove))}})}
}

@Composable private fun LightCard(light:LightDevice,busy:Boolean,onPower:()->Unit,onOpen:()->Unit,onEdit:(()->Unit)?){
    val aura=if(light.state.mode=="color")Color.hsv(light.state.color.hue.toFloat(),light.state.color.saturation/100f,light.state.color.value/100f)else Color(0xFFFFD7A0)
    Card(onClick=onOpen,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=androidx.compose.foundation.BorderStroke(1.dp,if(light.state.online)aura.copy(alpha=.7f)else MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().background(aura.copy(alpha=if(light.state.power).12f else .03f)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=aura.copy(alpha=if(light.state.power).28f else .08f)){Icon(if(light.state.power)Lucide.Lightbulb else Lucide.LightbulbOff,null,Modifier.padding(12.dp).size(24.dp),tint=if(light.state.power)aura else MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.width(13.dp));Column(Modifier.weight(1f)){Text(light.name,fontWeight=FontWeight.SemiBold);Text(listOfNotNull(light.room.takeIf(String::isNotBlank),if(light.state.online)"${stringResource(R.string.available)} · ${light.state.brightness}%" else stringResource(R.string.unavailable)).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=if(light.state.online)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)}
            onEdit?.let{IconButton(onClick=it){Icon(Lucide.Pencil,stringResource(R.string.edit))}}
            if(busy)CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=2.dp)else Switch(checked=light.state.power,onCheckedChange={onPower()},enabled=light.state.online)
        }
    }
}

@Composable private fun LightControls(light:LightDevice,busy:Boolean,onDismiss:()->Unit,onChange:(LightStatePatch)->Unit){
    var brightness by remember(light.id,light.state.brightness){mutableFloatStateOf(light.state.brightness.coerceAtLeast(1).toFloat())};var temperature by remember(light.id,light.state.temperatureKelvin){mutableFloatStateOf(light.state.temperatureKelvin.toFloat())};var hue by remember(light.id,light.state.color.hue){mutableFloatStateOf(light.state.color.hue.toFloat())};var saturation by remember(light.id,light.state.color.saturation){mutableFloatStateOf(light.state.color.saturation.toFloat())};val preview=Color.hsv(hue,saturation/100f,brightness/100f)
    ValkyrisBottomSheet(title=light.name,onDismiss=onDismiss,dismissEnabled=!busy,actions={TextButton(onClick=onDismiss){Text(stringResource(R.string.close))}}){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(if(light.state.online)R.string.available else R.string.unavailable),Modifier.weight(1f),color=if(light.state.online)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error);Switch(light.state.power,{onChange(LightStatePatch(power=it))},enabled=light.state.online&&!busy)}
        Text(stringResource(R.string.brightness_value,brightness.toInt()),fontWeight=FontWeight.SemiBold);Slider(brightness,{brightness=it},valueRange=1f..100f,onValueChangeFinished={onChange(LightStatePatch(brightness=brightness.toInt()))},enabled=light.state.online)
        Text(stringResource(R.string.white_temperature,temperature.toInt()),fontWeight=FontWeight.SemiBold);Slider(temperature,{temperature=it},valueRange=2700f..6500f,onValueChangeFinished={onChange(LightStatePatch(temperatureKelvin=temperature.toInt()))},enabled=light.state.online)
        HorizontalDivider();Row(verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(44.dp),shape=CircleShape,color=preview){};Spacer(Modifier.width(12.dp));Text(stringResource(R.string.color),fontWeight=FontWeight.SemiBold)}
        Text(stringResource(R.string.hue_value,hue.toInt()));Slider(hue,{hue=it},valueRange=0f..360f,onValueChangeFinished={onChange(LightStatePatch(color=LightColor(hue.toInt(),saturation.toInt(),brightness.toInt())))},enabled=light.state.online)
        Text(stringResource(R.string.saturation_value,saturation.toInt()));Slider(saturation,{saturation=it},valueRange=0f..100f,onValueChangeFinished={onChange(LightStatePatch(color=LightColor(hue.toInt(),saturation.toInt(),brightness.toInt())))},enabled=light.state.online)
    }}
}

private data class ImportedLight(val name:String="",val id:String="",val key:String="",val ip:String="",val version:Double=3.3)

@Composable private fun LightEditor(light:LightDevice?,saving:Boolean,error:String?,onDismiss:()->Unit,onSave:(CreateLightRequest)->Unit,onRemove:(()->Unit)?=null,onTest:(()->Unit)?=null){
    var name by remember(light?.id){mutableStateOf(light?.name.orEmpty())};var room by remember(light?.id){mutableStateOf(light?.room.orEmpty())};var deviceId by remember(light?.id){mutableStateOf(light?.deviceId.orEmpty())};var key by remember(light?.id){mutableStateOf("")};var ip by remember(light?.id){mutableStateOf("")};var version by remember(light?.id){mutableStateOf((light?.protocolVersion?:3.3).toString())};val context=LocalContext.current
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?:return@rememberLauncherForActivityResult;runCatching{context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}.orEmpty()}.mapCatching(::readTinyTuya).onSuccess{item->name=item.name;deviceId=item.id;key=item.key;ip=item.ip;version=item.version.toString()}}
    ValkyrisBottomSheet(title=stringResource(if(light==null)R.string.add_lighting else R.string.edit_light),onDismiss=onDismiss,dismissEnabled=!saving,actions={onRemove?.let{TextButton(onClick=it,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(stringResource(R.string.remove))}};TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))};Button(onClick={onSave(CreateLightRequest(name.trim(),room.trim(),deviceId.trim(),key.trim(),ip.trim(),version.toDoubleOrNull()?:3.3))},enabled=!saving&&name.isNotBlank()&&deviceId.isNotBlank()&&(light!=null||key.length==16)){if(saving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Text(stringResource(R.string.save))}}){Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)};if(light==null)OutlinedButton(onClick={importer.launch("application/json")},Modifier.fillMaxWidth()){Icon(Lucide.FileUp,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.import_tinytuya))}
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.name))});OutlinedTextField(room,{room=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.room))});OutlinedTextField(deviceId,{deviceId=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.device_id))},enabled=light==null);OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text(stringResource(if(light==null)R.string.local_key else R.string.new_local_key))},visualTransformation=PasswordVisualTransformation());OutlinedTextField(ip,{ip=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.initial_ip))});OutlinedTextField(version,{version=it.filter{c->c.isDigit()||c=='.'}},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.tuya_protocol))});Text(stringResource(R.string.light_credentials_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);onTest?.let{OutlinedButton(onClick=it,enabled=!saving){Icon(Lucide.RefreshCw,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.test_connection))}}
    }}
}

private fun readTinyTuya(raw:String):ImportedLight{val root=Json.parseToJsonElement(raw);val array=when(root){is JsonArray->root;is JsonObject->root["devices"]?.jsonArray?:error("Arquivo sem dispositivos");else->error("Arquivo inválido")};val item=array.firstOrNull()?.jsonObject?:error("Nenhum dispositivo no arquivo");return ImportedLight(item["name"]?.jsonPrimitive?.content.orEmpty(),item["id"]?.jsonPrimitive?.content.orEmpty(),item["key"]?.jsonPrimitive?.content.orEmpty(),item["ip"]?.jsonPrimitive?.content.orEmpty(),item["version"]?.jsonPrimitive?.doubleOrNull?:3.3)}
