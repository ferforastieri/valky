package com.ferforastieri.valkyris.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.LightStatePatch
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LightsState(val loading:Boolean=true,val busy:Set<String> = emptySet(),val lights:List<LightDevice> = emptyList(),val error:String?=null)

@HiltViewModel
class LightsViewModel @Inject constructor(private val repository:ValkyrisRepository,private val actionGate:MobileActionGate):ViewModel(){
    private val _state=MutableStateFlow(LightsState());val state=_state.asStateFlow()
    private var realtime:okhttp3.WebSocket?=null;private var active=true
    init{
        viewModelScope.launch{repository.lights.collect{value->_state.update{it.copy(lights=value,loading=false)}}}
        refresh();connectRealtime()
        viewModelScope.launch{while(isActive){delay(20_000);runCatching{repository.refreshLights()}}}
    }
    private fun connectRealtime(){realtime=repository.api.realtime({refresh()},{if(active)viewModelScope.launch{delay(5_000);if(active)connectRealtime()}},eventPrefix="light.")}
    fun refresh()=viewModelScope.launch{runCatching{repository.refreshLights()}.onFailure{error->_state.update{it.copy(loading=false,error=error.message)}}}
    fun control(id:String,patch:LightStatePatch)=guarded(id,false){repository.controlLight(id,patch)}
    fun delete(id:String,done:()->Unit={})=guarded(id){repository.deleteLight(id);done()}
    private fun guarded(id:String,useGlobalGate:Boolean=true,block:suspend()->Unit){if(id in _state.value.busy||(useGlobalGate&&!actionGate.tryAcquire()))return;_state.update{it.copy(busy=it.busy+id,error=null)};viewModelScope.launch{try{runCatching{block()}.onFailure{error->_state.update{it.copy(error=error.message)}}}finally{_state.update{it.copy(busy=it.busy-id)};if(useGlobalGate)actionGate.release()}}}
    override fun onCleared(){active=false;realtime?.close(1000,"lights screen closed");super.onCleared()}
}
