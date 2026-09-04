package com.ferforastieri.valkyris

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.model.UpdateInfo
import com.ferforastieri.valkyris.core.preferences.AppPreferences
import com.ferforastieri.valkyris.core.push.FcmRegistration
import com.ferforastieri.valkyris.core.security.Session
import com.ferforastieri.valkyris.core.security.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class ApkDownload(val url:String,val version:String)

@HiltViewModel class MainViewModel @Inject constructor(private val api:ValkyrisApi,private val sessions:SessionStore,private val preferences:AppPreferences,private val push:FcmRegistration):ViewModel(){
    val notices=api.notices
    private val _paired=MutableStateFlow(sessions.get()!=null);val paired=_paired.asStateFlow()
    private val _admin=MutableStateFlow(sessions.get()?.admin==true);val admin=_admin.asStateFlow()
    private val _connecting=MutableStateFlow(false);val connecting=_connecting.asStateFlow()
    private val _authInitialized=MutableStateFlow<Boolean?>(null);val authInitialized=_authInitialized.asStateFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private val _pendingEvent=MutableStateFlow<String?>(null);val pendingEvent=_pendingEvent.asStateFlow()
    private val _pendingCamera=MutableStateFlow<String?>(null);val pendingCamera=_pendingCamera.asStateFlow()
    private val _updateInfo=MutableStateFlow<UpdateInfo?>(null);val updateInfo=_updateInfo.asStateFlow()
    private val _updating=MutableStateFlow(false);val updating=_updating.asStateFlow()
    private val _apkDownloads=MutableSharedFlow<ApkDownload>(extraBufferCapacity=1);val apkDownloads=_apkDownloads.asSharedFlow()
    private var lastUpdateCheck=0L
    val theme=preferences.theme.stateIn(viewModelScope,SharingStarted.Eagerly,"system")
    val language=preferences.language.stateIn(viewModelScope,SharingStarted.Eagerly,"system")
    fun acceptPairingLink(uri:Uri?){if(uri?.scheme!="valkyris"||uri.host!="pair")return;val url=uri.getQueryParameter("url").orEmpty();val code=uri.getQueryParameter("code").orEmpty();val fingerprint=uri.getQueryParameter("fingerprint").orEmpty();if(url.startsWith("https://")&&code.isNotBlank())pair(url,code,fingerprint)}
    fun acceptLaunch(uri:Uri?,eventId:String?,cameraId:String?=null){acceptPairingLink(uri);val deepLinkId=if(uri?.scheme=="valkyris"&&uri.host=="event")uri.lastPathSegment else null;val deepCameraId=if(uri?.scheme=="valkyris"&&uri.host=="camera")uri.lastPathSegment else null;_pendingCamera.value=cameraId?.takeIf(String::isNotBlank)?:deepCameraId?.takeIf(String::isNotBlank);_pendingEvent.value=if(_pendingCamera.value==null)eventId?.takeIf(String::isNotBlank)?:deepLinkId?.takeIf(String::isNotBlank) else null}
    fun eventOpened(){_pendingEvent.value=null}
    fun cameraOpened(){_pendingCamera.value=null}
    fun resetAuthStatus(){_authInitialized.value=null;_error.value=null}
    fun inspectServer(url:String){val base=url.trim().trimEnd('/');if(!base.startsWith("https://")){_error.value="Use um endereço HTTPS válido.";return};viewModelScope.launch{_connecting.value=true;_error.value=null;runCatching{api.authStatus(base)}.onSuccess{_authInitialized.value=it.initialized}.onFailure{_error.value=it.message};_connecting.value=false}}
    fun login(url:String,password:String,bootstrap:Boolean){val base=url.trim().trimEnd('/');if(!base.startsWith("https://")||password.isBlank()){_error.value="Use um endereço HTTPS e informe a senha da casa.";return};viewModelScope.launch{_connecting.value=true;_error.value=null;runCatching{api.login(base,com.ferforastieri.valkyris.core.model.LoginRequest(password,android.os.Build.MODEL,Locale.getDefault().toLanguageTag()),bootstrap)}.onSuccess{sessions.save(Session(base,it.token,admin=it.admin));_admin.value=it.admin;_paired.value=true;push.registerCurrent();checkForUpdates(force=true)}.onFailure{_error.value=it.message};_connecting.value=false}}
    private fun pair(url:String,code:String,fingerprint:String){viewModelScope.launch{_connecting.value=true;_error.value=null;runCatching{api.pair(url,fingerprint,com.ferforastieri.valkyris.core.model.PairRequest(code,android.os.Build.MODEL,Locale.getDefault().toLanguageTag()))}.onSuccess{sessions.save(Session(url,it.token,fingerprint,it.admin));_admin.value=it.admin;_paired.value=true;push.registerCurrent()}.onFailure{_error.value=it.message};_connecting.value=false}}
    fun signOut(){sessions.clear();_admin.value=false;_paired.value=false;_updateInfo.value=null}
    fun checkForUpdates(force:Boolean=false){if(!_paired.value)return;val now=android.os.SystemClock.elapsedRealtime();if(!force&&lastUpdateCheck!=0L&&now-lastUpdateCheck<15*60_000)return;lastUpdateCheck=now;viewModelScope.launch{runCatching{api.updateInfo()}.onSuccess{_updateInfo.value=it.takeIf(UpdateInfo::available)}}}
    fun dismissUpdate(){_updateInfo.value=null}
    fun startUpdate(){val available=_updateInfo.value?:return;viewModelScope.launch{_updating.value=true;val result=if(_admin.value)runCatching{api.startUpdate()}else Result.success(available);result.onSuccess{info->_updateInfo.value=null;if(info.apkUrl.isNotBlank())_apkDownloads.emit(ApkDownload(info.apkUrl,info.latestVersion))}.onFailure{_error.value=it.message};_updating.value=false}}
    fun setTheme(value:String){viewModelScope.launch{preferences.setTheme(value)}}
    fun setLanguage(value:String){viewModelScope.launch{preferences.setLanguage(value)}}
}
