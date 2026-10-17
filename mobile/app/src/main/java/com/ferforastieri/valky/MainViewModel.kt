package com.ferforastieri.valky

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valky.core.action.MobileActionGate
import com.ferforastieri.valky.core.model.UpdateInfo
import com.ferforastieri.valky.core.network.ValkyApi
import com.ferforastieri.valky.core.preferences.AppPreferences
import com.ferforastieri.valky.core.push.FcmRegistration
import com.ferforastieri.valky.core.security.Session
import com.ferforastieri.valky.core.security.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ApkDownload(val url: String, val version: String)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val api: ValkyApi,
    private val repository: com.ferforastieri.valky.core.network.ValkyRepository,
    private val sessions: SessionStore,
    private val preferences: AppPreferences,
    private val push: FcmRegistration,
    private val actionGate: MobileActionGate,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val previousServer: String get() = sessions.savedBase()
    val permissions = api.permissions
    val permissionsLoaded = api.permissionsLoaded
    private val _pairingLink = MutableStateFlow<Uri?>(null)
    val pairingLink = _pairingLink.asStateFlow()
    val notices = api.notices
    val actionBusy = actionGate.busy
    private val _paired = MutableStateFlow(sessions.get() != null)
    val paired = _paired.asStateFlow()
    private val _admin = MutableStateFlow(sessions.get()?.admin == true)
    val admin = _admin.asStateFlow()
    private val _connecting = MutableStateFlow(false)
    val connecting = _connecting.asStateFlow()
    private val _authInitialized = MutableStateFlow<Boolean?>(null)
    val authInitialized = _authInitialized.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _pendingEvent = MutableStateFlow<String?>(null)
    val pendingEvent = _pendingEvent.asStateFlow()
    private val _pendingCamera = MutableStateFlow<String?>(null)
    val pendingCamera = _pendingCamera.asStateFlow()
    // Home screen widgets can ask the app to open a specific screen.
    private val _pendingSection = MutableStateFlow<String?>(null)
    val pendingSection = _pendingSection.asStateFlow()
    // Puxar a tela de cima para baixo atualiza tudo, em qualquer tela.
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    private val _updating = MutableStateFlow(false)
    val updating = _updating.asStateFlow()
    private val _apkDownloads = MutableSharedFlow<ApkDownload>(extraBufferCapacity = 1)
    val apkDownloads = _apkDownloads.asSharedFlow()
    private var lastUpdateCheck = 0L
    val theme = preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val language = preferences.language.stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    fun acceptPairingLink(uri: Uri?) {
        if (_paired.value || uri?.scheme != "valky" || uri.host != "pair") return
        val url = uri.getQueryParameter("url").orEmpty()
        if (url.startsWith("https://") && !uri.getQueryParameter("code").isNullOrBlank()) {
            _pairingLink.value = uri
            _authInitialized.value = true
            _error.value = null
        }
    }
    fun cancelInvitation() { _pairingLink.value = null; resetAuthStatus() }
    fun registerAccount(username: String, password: String, name: String) {
        val uri = _pairingLink.value ?: return
        pair(uri.getQueryParameter("url").orEmpty(), uri.getQueryParameter("code").orEmpty(), uri.getQueryParameter("fingerprint").orEmpty(), name, username, password)
    }
    fun acceptLaunch(uri: Uri?, eventId: String?, cameraId: String? = null, section: String? = null) {
        acceptPairingLink(uri)
        val deepLinkId = if (uri?.scheme == "valky" && uri.host == "event") uri.lastPathSegment else null
        val deepCameraId = if (uri?.scheme == "valky" && uri.host == "camera") uri.lastPathSegment else null
        val sectionCameraId = section?.takeIf { it.startsWith("camera:") }?.substringAfter("camera:")
        val sectionRoute = section?.takeIf { !it.startsWith("camera:") }
        _pendingCamera.value = cameraId?.takeIf(String::isNotBlank) ?: sectionCameraId?.takeIf(String::isNotBlank) ?: deepCameraId?.takeIf(String::isNotBlank)
        _pendingEvent.value = if (_pendingCamera.value == null) eventId?.takeIf(String::isNotBlank) ?: deepLinkId?.takeIf(String::isNotBlank) else null
        _pendingSection.value = if (_pendingCamera.value == null) sectionRoute?.takeIf(String::isNotBlank) else null
    }

    fun refreshEverything() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try { repository.refreshAll() } finally { _refreshing.value = false }
        }
    }

    fun eventOpened() { _pendingEvent.value = null }
    fun cameraOpened() { _pendingCamera.value = null }
    fun sectionOpened() { _pendingSection.value = null }
    fun resetAuthStatus() { _authInitialized.value = null; _error.value = null }

    fun inspectServer(url: String) {
        val base = url.trim().replace(Regex("/+$"), "")
        if (!base.startsWith("https://")) { _error.value = "Use um endereço HTTPS válido."; return }
        if (!actionGate.tryAcquire()) return
        _connecting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                runCatching { api.authStatus(base, sessions.savedFingerprint(base)) }.onSuccess { _authInitialized.value = it.initialized }.onFailure { _error.value = it.message }
            } finally {
                _connecting.value = false
                actionGate.release()
            }
        }
    }

    fun login(url: String, password: String, bootstrap: Boolean, userName: String, username: String) {
        val base = url.trim().replace(Regex("/+$"), "")
        if (!base.startsWith("https://") || password.isBlank()) { _error.value = "Use um endereço HTTPS e informe seu usuário e senha."; return }
        if (!actionGate.tryAcquire()) return
        _connecting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                runCatching { api.login(base, com.ferforastieri.valky.core.model.LoginRequest(password, android.os.Build.MODEL, userName.trim(), Locale.getDefault().toLanguageTag(), username.trim()), bootstrap, sessions.savedFingerprint(base)) }
                    .onSuccess { sessions.save(Session(base, it.token, fingerprint = sessions.savedFingerprint(base), admin = it.admin)); _admin.value = it.admin; _paired.value = true; runCatching { api.sessionPermissions() }; push.registerCurrent(); checkForUpdates(force = true) }
                    .onFailure { _error.value = it.message }
            } finally {
                _connecting.value = false
                actionGate.release()
            }
        }
    }

    private fun pair(url: String, code: String, fingerprint: String, userName: String, username: String, password: String) {
        if (!actionGate.tryAcquire()) return
        _connecting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                runCatching { api.pair(url, fingerprint, com.ferforastieri.valky.core.model.PairRequest(code, android.os.Build.MODEL, userName.trim(), Locale.getDefault().toLanguageTag(), username.trim(), password)) }
                    .onSuccess { sessions.save(Session(url, it.token, fingerprint, it.admin)); _admin.value = it.admin; _paired.value = true; _pairingLink.value = null; runCatching { api.sessionPermissions() }; push.registerCurrent(); checkForUpdates(force = true) }
                    .onFailure { _error.value = it.message }
            } finally {
                _connecting.value = false
                actionGate.release()
            }
        }
    }

    fun signOut() { sessions.clear(); com.ferforastieri.valky.feature.people.LocationTrackingService.stop(context); api.clearPermissions(); _pairingLink.value = null; _admin.value = false; _paired.value = false; _updateInfo.value = null; _updating.value = false; lastUpdateCheck = 0L }

    fun refreshPushRegistration() {
        val current = sessions.get() ?: return
        push.registerCurrent()
        viewModelScope.launch {
            runCatching { api.sessionPermissions() }.onSuccess { permissions ->
                if (sessions.get()?.token == current.token) {
                    _admin.value = permissions.admin
                    if (current.admin != permissions.admin) sessions.save(current.copy(admin = permissions.admin))
                }
            }.onFailure {
                if (sessions.get()?.token == current.token) {
                    _admin.value = false
                    if ((it as? com.ferforastieri.valky.core.network.ApiException)?.status == 401) signOut()
                }
            }
        }
    }

    fun checkForUpdates(force: Boolean = false) {
        if (!_paired.value) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastUpdateCheck != 0L && now - lastUpdateCheck < 15 * 60_000) return
        lastUpdateCheck = now
        val session = sessions.get()
        viewModelScope.launch {
            runCatching { api.updateInfo() }.onSuccess { info ->
                if (!_paired.value || sessions.get() != session) return@onSuccess
                _updateInfo.value = info.takeIf { it.apkUpdateAvailable && it.apkUrl.isNotBlank() }
            }
        }
    }

    fun startUpdate() {
        val available = _updateInfo.value ?: return
        if (!available.apkUpdateAvailable || available.apkUrl.isBlank() || !actionGate.tryAcquire()) return
        _updating.value = true
        viewModelScope.launch {
            try {
                _apkDownloads.emit(ApkDownload(available.apkUrl, available.latestVersion))
            } finally {
                _updating.value = false
                actionGate.release()
            }
        }
    }

    fun setTheme(value: String) { viewModelScope.launch { preferences.setTheme(value) } }
    fun setLanguage(value: String) { viewModelScope.launch { preferences.setLanguage(value) } }
}
