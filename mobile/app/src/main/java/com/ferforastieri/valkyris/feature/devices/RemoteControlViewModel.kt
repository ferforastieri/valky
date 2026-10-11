package com.ferforastieri.valkyris.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.model.RemoteApp
import com.ferforastieri.valkyris.core.model.RemoteDevice
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteControlState(
    val remote: RemoteDevice? = null,
    val apps: List<RemoteApp> = emptyList(),
    val appsLoading: Boolean = false,
    val busy: Boolean = false,
    val pairing: Boolean = false,
    val powering: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val api: ValkyrisApi,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: String = checkNotNull(saved["id"])
    private val _state = MutableStateFlow(RemoteControlState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { api.remotes().firstOrNull { it.id == id } }
                .onSuccess { remote ->
                    _state.update { it.copy(remote = remote) }
                    if (remote?.tokenConfigured == true) loadApps()
                }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun send(key: String) {
        // Keys are fire-and-forget so a tap never waits for the previous one, and
        // the device state is not rewritten on every key (the backend keeps a
        // warm channel to the TV), which keeps the remote responsive.
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            runCatching { api.sendRemoteKey(id, key) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun pair() {
        if (_state.value.pairing) return
        _state.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            try {
                runCatching { api.pairRemote(id) }
                    .onSuccess { updated ->
                        _state.update { it.copy(remote = updated) }
                        loadApps()
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(pairing = false) }
            }
        }
    }

    fun loadApps() {
        if (_state.value.appsLoading) return
        _state.update { it.copy(appsLoading = true, error = null) }
        viewModelScope.launch {
            try {
                runCatching { api.remoteApps(id) }
                    .onSuccess { apps -> _state.update { it.copy(apps = apps) } }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(appsLoading = false) }
            }
        }
    }

    fun launch(appId: String) {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            runCatching { api.launchRemoteApp(id, appId) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun power() {
        if (_state.value.powering) return
        _state.update { it.copy(powering = true, error = null) }
        viewModelScope.launch {
            try {
                runCatching { api.powerRemote(id) }
                    .onSuccess { updated -> _state.update { it.copy(remote = updated) } }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(powering = false) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
