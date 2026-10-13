package com.ferforastieri.valkyris.feature.lights

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import com.composables.icons.lucide.Wifi
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.design.ValkyrisBottomSheet
import com.ferforastieri.valkyris.core.model.*

@Composable fun LightsScreen(admin:Boolean,vm:LightsViewModel=hiltViewModel()){
    LifecycleResumeEffect(Unit){vm.refresh();vm.continueManual();onPauseOrDispose{vm.pausePairing()}}
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember{mutableStateOf<LightDevice?>(null)}
    var removing by remember{mutableStateOf<LightDevice?>(null)}
    var renaming by remember{mutableStateOf<LightDevice?>(null)}
    var pairing by remember{mutableStateOf(false)}
    val context=LocalContext.current
    var pendingPairing by remember{mutableStateOf<PendingPairing?>(null)}
    val locationNeededNotice=stringResource(R.string.light_pair_location_needed)
    val locationOffNotice=stringResource(R.string.light_pair_location_off)
    val neededPermissions=remember{
        if(android.os.Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){granted->
        val pending=pendingPairing
        pendingPairing=null
        when{
            !neededPermissions.all{granted[it]==true}->vm.pairingNotice(locationNeededNotice)
            pending==null->{}
            !vm.locationEnabled()->vm.pairingNotice(locationOffNotice)
            else->vm.configure(pending.ssid,pending.password,pending.remember,pending.lampSsid)
        }
    }
    // O Android só deixa procurar redes com a Localização liberada: o pedido
    // acontece aqui mesmo, na hora, e o fluxo continua sozinho após a resposta.
    fun beginPairing(ssid:String,password:String,lampSsid:String?,remember:Boolean){
        when{
            !vm.hasPairingPermissions()->{pendingPairing=PendingPairing(ssid,password,lampSsid,remember);locationPermission.launch(neededPermissions)}
            !vm.locationEnabled()->vm.pairingNotice(locationOffNotice)
            else->vm.configure(ssid,password,remember,lampSsid)
        }
    }
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
            onClick={pairing=true},
            modifier=Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=18.dp),
            containerColor=MaterialTheme.colorScheme.secondary,
            contentColor=MaterialTheme.colorScheme.onSecondary,
        ){Icon(Lucide.Plus,stringResource(R.string.add_light))}
    }
    // Tudo automático: ao abrir a folha do +, se a senha do Wi-Fi já está
    // guardada o pareamento começa sozinho — sem tocar em nada.
    LaunchedEffect(pairing, state.pairing) {
        val autoNetwork=vm.suggestedSsid().ifBlank{vm.savedNetwork()}
        if(pairing&&state.pairing==null&&vm.savedPassword().isNotBlank()&&autoNetwork.isNotBlank()){
            beginPairing(autoNetwork,vm.savedPassword(),null,true)
        }
    }
    if(pairing)LightPairingSheet(
        progress=state.pairing,
        network=remember{vm.suggestedSsid().ifBlank{vm.savedNetwork()}},
        savedPassword=remember{vm.savedPassword()},
        lampNetworks=state.lampNetworks,
        locationOff=!vm.locationEnabled(),
        manualWifi=state.pairing?.phase=="manual-wifi",
        onConfigure={ssid,password->beginPairing(ssid,password,null,true)},
        onPickLamp={lampSsid,password->beginPairing(state.network,password,lampSsid,false)},
        onOpenLocation={runCatching{context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}},
        onOpenWifi={runCatching{context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}},
        onDismiss={pairing=false;vm.dismissPairing()},
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
    Card(onClick=onOpen,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,if(light.state.online)aura.copy(alpha=.7f)else MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().background(aura.copy(alpha=if(light.state.power).12f else .03f)).padding(16.dp),verticalAlignment=Alignment.CenterVertically){
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

/**
 * Folha do pareamento pelo caminho direto (modo AP) — a mais simples possível:
 * a rede do celular, a senha (pedida uma única vez e depois guardada) e o botão
 * de configurar. Sem texto explicativo.
 */
@Composable private fun LightPairingSheet(
    progress:PairingProgress?,
    network:String,
    savedPassword:String,
    lampNetworks:List<String>,
    locationOff:Boolean,
    manualWifi:Boolean,
    onConfigure:(String,String)->Unit,
    onPickLamp:(String,String)->Unit,
    onOpenLocation:()->Unit,
    onOpenWifi:()->Unit,
    onDismiss:()->Unit,
){
    var password by remember{mutableStateOf(savedPassword)}
    var editingPassword by remember{mutableStateOf(savedPassword.isBlank())}
    val paired=progress?.light
    val failed=progress?.phase=="failed"
    val running=progress!=null&&!failed&&progress.phase!="paired"&&progress.phase!="choose-lamp"&&progress.phase!="manual-wifi"
    ValkyrisBottomSheet(
        title=stringResource(R.string.add_light),
        onDismiss=onDismiss,
        dismissEnabled=!running,
        actions={
            when{
                paired!=null->Button(onClick=onDismiss){Text(stringResource(R.string.close))}
                running->TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}
                else->{
                    TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}
                    Button(
                        onClick={onConfigure(network,password)},
                        enabled=network.isNotBlank()&&password.isNotBlank(),
                    ){Text(stringResource(R.string.light_pair_action))}
                }
            }
        },
    ){
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            when{
                paired!=null->Row(verticalAlignment=Alignment.CenterVertically){
                    Surface(shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){Icon(Lucide.Lightbulb,null,Modifier.padding(10.dp).size(22.dp),tint=MaterialTheme.colorScheme.onSecondaryContainer)}
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.light_pair_done),fontWeight=FontWeight.SemiBold)
                }
                running->Row(verticalAlignment=Alignment.CenterVertically){
                    CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress?.message.orEmpty().ifBlank{stringResource(R.string.light_pair_sending)},Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                }
                progress?.phase=="choose-lamp"->Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(progress.message,style=MaterialTheme.typography.bodyMedium)
                    if(editingPassword)OutlinedTextField(
                        password,{password=it},Modifier.fillMaxWidth(),
                        label={Text(stringResource(R.string.light_pair_password))},
                        visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                        singleLine=true,
                    )
                    lampNetworks.forEach{name->
                        Surface(onClick={if(password.isBlank())editingPassword=true else onPickLamp(name,password)},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant){
                            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                                Icon(Lucide.Wifi,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(10.dp))
                                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                Icon(Lucide.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    TextButton({onConfigure(network,password)}){Text(stringResource(R.string.light_pair_search_again))}
                }
                else->Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    progress?.message?.takeIf{it.isNotBlank()&&(failed||manualWifi)}?.let{Text(it,color=if(failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodySmall)}
                    if(failed&&locationOff)TextButton({onOpenLocation}){Text(stringResource(R.string.light_pair_open_location))}
                    if(manualWifi)TextButton({onOpenWifi}){Text(stringResource(R.string.light_pair_open_wifi))}
                    Text(stringResource(R.string.light_pair_network_auto,network.ifBlank{stringResource(R.string.light_pair_network_unknown)}),style=MaterialTheme.typography.bodyMedium)
                    if(editingPassword)OutlinedTextField(
                        password,{password=it},Modifier.fillMaxWidth(),
                        label={Text(stringResource(R.string.light_pair_password))},
                        visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
                        keyboardActions=KeyboardActions(onDone={if(network.isNotBlank()&&password.isNotBlank())onConfigure(network,password)}),
                        singleLine=true,
                    ) else Row(verticalAlignment=Alignment.CenterVertically){
                        Text(stringResource(R.string.light_pair_password_saved),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={editingPassword=true}){Text(stringResource(R.string.light_pair_password_change))}
                    }
                }
            }
        }
    }
}

/** Dados guardados enquanto o Android pergunta a permissão de Localização. */
private class PendingPairing(val ssid:String,val password:String,val lampSsid:String?,val remember:Boolean)

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
