package com.ferforastieri.valkyris.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                    .onSuccess { updated -> _state.update { it.copy(remote = updated) } }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(pairing = false) }
            }
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
