package com.ferforastieri.valkyris.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.LightCandidate
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.LightRegistration
import com.ferforastieri.valkyris.core.model.LightStatePatch
import com.ferforastieri.valkyris.core.model.LightUpdate
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LightsState(
    val loading: Boolean = true,
    val busy: Set<String> = emptySet(),
    val lights: List<LightDevice> = emptyList(),
    val error: String? = null,
    /** Lâmpadas encontradas na rede local, para preencher o cadastro. */
    val candidates: List<LightCandidate> = emptyList(),
    val scanning: Boolean = false,
    val registering: Boolean = false,
    /** Erro do cadastro, separado do erro geral da tela. */
    val registrationError: String? = null,
)

@HiltViewModel
class LightsViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    private val _state = MutableStateFlow(LightsState())
    val state = _state.asStateFlow()
    private var realtime: okhttp3.WebSocket? = null
    // O canal só fica de pé enquanto a tela está à frente. Antes ele subia junto
    // com o aplicativo e seguia aberto em segundo plano, recarregando as listas
    // de minuto em minuto a noite inteira.
    private var active = false

    init {
        viewModelScope.launch { repository.lights.collect { value -> _state.update { it.copy(lights = value, loading = false) } } }
        refresh()
    }

    /** Liga o canal quando a tela aparece e o desliga quando ela sai de vista. */
    fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        if (value) {
            connectRealtime(); refresh()
        } else {
            realtime?.close(1000, "tela fora de vista")
            realtime = null
        }
    }

    private fun connectRealtime() {
        realtime = repository.api.realtime({ refresh() }, { if (active) viewModelScope.launch { delay(5_000); if (active) connectRealtime() } }, eventPrefix = "light.")
    }

    fun refresh() = viewModelScope.launch { runCatching { repository.refreshLights() }.onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } } }
    fun control(id: String, patch: LightStatePatch) = guarded(id, false) { repository.controlLight(id, patch) }
    fun toggleFavorite(id: String, favorite: Boolean) = viewModelScope.launch { runCatching { repository.api.setFavorite("light", id, favorite) }.onSuccess { repository.refreshLights() }.onFailure { error -> _state.update { it.copy(error = error.message) } } }
    fun delete(id: String, done: () -> Unit = {}) = guarded(id) { repository.deleteLight(id); done() }

    fun rename(id: String, name: String, done: (Boolean) -> Unit = {}) {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { repository.updateLight(id, LightUpdate(name = name)) }
                    .onSuccess { done(true) }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                actionGate.release()
            }
        }
    }

    /**
     * Procura lâmpadas na rede local. A busca acontece no servidor, então não
     * depende de permissão de Wi-Fi nem da Localização do celular.
     */
    fun scanForLights() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, registrationError = null) }
        viewModelScope.launch {
            runCatching { repository.discoverLights() }
                .onSuccess { found -> _state.update { it.copy(candidates = found, scanning = false) } }
                .onFailure { error -> _state.update { it.copy(scanning = false, registrationError = error.message) } }
        }
    }

    /** Adota uma lâmpada que já foi pareada no aplicativo do fabricante. */
    fun register(input: LightRegistration, done: (Boolean) -> Unit = {}) {
        if (_state.value.registering || !actionGate.tryAcquire()) return
        _state.update { it.copy(registering = true, registrationError = null) }
        viewModelScope.launch {
            try {
                runCatching { repository.registerLight(input) }
                    .onSuccess {
                        _state.update { it.copy(registering = false, candidates = emptyList()) }
                        done(true)
                    }
                    .onFailure { error ->
                        _state.update { it.copy(registering = false, registrationError = error.message) }
                        done(false)
                    }
            } finally {
                actionGate.release()
            }
        }
    }

    fun clearRegistration() = _state.update { it.copy(registrationError = null, candidates = emptyList(), scanning = false) }

    private fun guarded(id: String, useGlobalGate: Boolean = true, block: suspend () -> Unit) {
        if (id in _state.value.busy || (useGlobalGate && !actionGate.tryAcquire())) return
        _state.update { it.copy(busy = it.busy + id, error = null) }
        viewModelScope.launch {
            try {
                runCatching { block() }.onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                _state.update { it.copy(busy = it.busy - id) }
                if (useGlobalGate) actionGate.release()
            }
        }
    }

    override fun onCleared() {
        active = false
        realtime?.close(1000, "lights screen closed")
        super.onCleared()
    }
}
