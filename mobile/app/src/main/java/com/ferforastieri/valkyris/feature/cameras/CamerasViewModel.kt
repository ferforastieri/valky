package com.ferforastieri.valkyris.feature.cameras

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.core.model.PTZCommand
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CamerasState(val loading:Boolean=true,val creating:Boolean=false,val cameras:List<Camera> = emptyList(),val error:String?=null)
@HiltViewModel class CamerasViewModel @Inject constructor(private val api:ValkyrisApi):ViewModel(){private val _state=MutableStateFlow(CamerasState());val state=_state.asStateFlow();init{refresh()};fun refresh(){viewModelScope.launch{val current=_state.value;_state.value=current.copy(loading=true,error=null);runCatching{api.cameras()}.onSuccess{_state.value=CamerasState(loading=false,creating=current.creating,cameras=it)}.onFailure{_state.value=current.copy(loading=false,error=it.message)}}};fun add(input:CreateCameraRequest){viewModelScope.launch{_state.value=_state.value.copy(creating=true,error=null);runCatching{api.createCamera(input)}.onSuccess{_state.value=_state.value.copy(creating=false);refresh()}.onFailure{_state.value=_state.value.copy(creating=false,error=it.message)}}}}
@HiltViewModel class CameraLiveViewModel @Inject constructor(private val api:ValkyrisApi,saved:SavedStateHandle):ViewModel(){val id:String=checkNotNull(saved["id"]);private val _camera=MutableStateFlow<Camera?>(null);val camera=_camera.asStateFlow();init{viewModelScope.launch{_camera.value=api.cameras().firstOrNull{it.id==id}}};fun move(pan:Double,tilt:Double,zoom:Double=0.0){viewModelScope.launch{runCatching{api.ptz(id,PTZCommand("move",pan,tilt,zoom))}}};fun stop(){viewModelScope.launch{runCatching{api.ptz(id,PTZCommand("stop"))}}};fun liveUrl()=api.liveUrl(id);fun token()=api.token();fun httpClient()=api.mediaHttpClient()}
