package com.ferforastieri.valkyris.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.RemoteDevice
import com.ferforastieri.valkyris.core.model.RemoteInput
import com.ferforastieri.valkyris.core.model.RemoteUpdate
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemotesState(
    val loading: Boolean = true,
    val busy: Set<String> = emptySet(),
    val pairing: Set<String> = emptySet(),
    val remotes: List<RemoteDevice> = emptyList(),
    val discovering: Boolean = false,
    val discovered: List<DiscoveredTv> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class RemotesViewModel @Inject constructor(
    private val api: ValkyrisApi,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    private val _state = MutableStateFlow(RemotesState())
    val state = _state.asStateFlow()

    fun refresh() = viewModelScope.launch {
        runCatching { api.remotes() }
            .onSuccess { items -> _state.update { it.copy(loading = false, remotes = items, error = null) } }
            .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
    }

    /** Registers a TV found on the network and starts pairing right away. */
    fun addAndPair(input: RemoteInput) {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { api.createRemote(input) }
                    .onSuccess { created ->
                        _state.update { it.copy(remotes = (it.remotes + created).distinctBy(RemoteDevice::id), discovered = emptyList(), error = null) }
                        pair(created.id)
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                actionGate.release()
            }
        }
    }

    fun discover(context: android.content.Context) {
        if (_state.value.discovering) return
        _state.update { it.copy(discovering = true, discovered = emptyList(), error = null) }
        viewModelScope.launch {
            try {
                val found = TvDiscovery.discover(context)
                _state.update { it.copy(discovering = false, discovered = found) }
            } catch (error: Exception) {
                _state.update { it.copy(discovering = false, error = error.message) }
            }
        }
    }

    fun update(id: String, input: RemoteUpdate, done: (Boolean) -> Unit = {}) {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { api.updateRemote(id, input) }
                    .onSuccess { updated ->
                        _state.update { it.copy(remotes = it.remotes.map { item -> if (item.id == id) updated else item }, error = null) }
                        done(true)
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                actionGate.release()
            }
        }
    }

    fun pair(id: String) {
        if (id in _state.value.pairing || id in _state.value.busy) return
        _state.update { it.copy(pairing = it.pairing + id, error = null) }
        viewModelScope.launch {
            try {
                runCatching { api.pairRemote(id) }
                    .onSuccess { updated ->
                        _state.update { it.copy(remotes = it.remotes.map { item -> if (item.id == id) updated else item }) }
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(pairing = it.pairing - id) }
            }
        }
    }

    fun send(id: String, key: String) {
        if (id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + id, error = null) }
        viewModelScope.launch {
            try {
                runCatching { api.sendRemoteKey(id, key) }
                    .onSuccess { updated ->
                        _state.update { it.copy(remotes = it.remotes.map { item -> if (item.id == id) updated else item }) }
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(busy = it.busy - id) }
            }
        }
    }

    fun remove(id: String, done: () -> Unit = {}) {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { api.deleteRemote(id) }
                    .onSuccess {
                        _state.update { it.copy(remotes = it.remotes.filterNot { item -> item.id == id }, error = null) }
                        done()
                    }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                actionGate.release()
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch {
            runCatching { api.setFavorite("remote", id, favorite) }
                .onSuccess { refresh() }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }
}
