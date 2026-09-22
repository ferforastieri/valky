package com.ferforastieri.valkyris.feature.lights

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
    val state by vm.state.collectAsStateWithLifecycle();var selected by remember{mutableStateOf<LightDevice?>(null)};var removing by remember{mutableStateOf<LightDevice?>(null)}
    Box(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)){
            state.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            when{
                state.loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                state.lights.isEmpty()->EmptyLighting()
                else->LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(start=0.dp,top=12.dp,end=0.dp,bottom=96.dp)){items(state.lights,key={it.id}){light->LightCard(light,light.id in state.busy,{vm.control(light.id,LightStatePatch(power=!light.state.power))},{selected=light},if(admin){{removing=light}}else null)}}
            }
        }
    }
    selected?.let{light->LightControls(light,light.id in state.busy,{selected=null}){vm.control(light.id,it)}}
    removing?.let{light->AlertDialog(onDismissRequest={removing=null},title={Text(stringResource(R.string.remove_light_title))},text={Text(stringResource(R.string.remove_light_body,light.name))},dismissButton={TextButton(onClick={removing=null}){Text(stringResource(R.string.cancel))}},confirmButton={Button(onClick={vm.delete(light.id){removing=null}},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(stringResource(R.string.remove))}})}
}

@Composable private fun EmptyLighting(){
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=3.dp){
            Column(Modifier.padding(horizontal=26.dp,vertical=24.dp).widthIn(max=340.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Icon(Lucide.Lightbulb,null,Modifier.padding(13.dp).size(28.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)}
                Text(stringResource(R.string.no_lights),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.no_lights_body),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun LightCard(light:LightDevice,busy:Boolean,onPower:()->Unit,onOpen:()->Unit,onRemove:(()->Unit)?){
    val aura=if(light.state.mode=="color")Color.hsv(light.state.color.hue.toFloat(),light.state.color.saturation/100f,light.state.color.value/100f)else Color(0xFFFFD7A0)
    Card(onClick=onOpen,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=androidx.compose.foundation.BorderStroke(1.dp,if(light.state.online)aura.copy(alpha=.7f)else MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().background(aura.copy(alpha=if(light.state.power).12f else .03f)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=aura.copy(alpha=if(light.state.power).28f else .08f)){Icon(if(light.state.power)Lucide.Lightbulb else Lucide.LightbulbOff,null,Modifier.padding(12.dp).size(24.dp),tint=if(light.state.power)aura else MaterialTheme.colorScheme.onSurfaceVariant)}
            Spacer(Modifier.width(13.dp));Column(Modifier.weight(1f)){Text(light.name,fontWeight=FontWeight.SemiBold);Text(listOfNotNull(light.room.takeIf(String::isNotBlank),if(light.state.online)"${stringResource(R.string.available)} · ${light.state.brightness}%" else stringResource(R.string.unavailable)).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=if(light.state.online)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)}
            onRemove?.let{IconButton(onClick=it){Icon(Lucide.Trash2,stringResource(R.string.remove),tint=MaterialTheme.colorScheme.onSurfaceVariant)}}
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
