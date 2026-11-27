package com.ferforastieri.valky.feature.lights

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valky.R
import com.ferforastieri.valky.core.action.MobileActionGate
import com.ferforastieri.valky.core.model.LightCandidate
import com.ferforastieri.valky.core.model.LightDevice
import com.ferforastieri.valky.core.model.LightRegistration
import com.ferforastieri.valky.core.model.LightStatePatch
import com.ferforastieri.valky.core.model.LightUpdate
import com.ferforastieri.valky.core.model.TuyaImportDevice
import com.ferforastieri.valky.core.model.TuyaImportFailure
import com.ferforastieri.valky.core.model.TuyaImportItem
import com.ferforastieri.valky.core.network.ValkyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

/** Estado do importador da Tuya; a chave local nunca chega ao aplicativo. */
data class TuyaImportState(
    val open: Boolean = false,
    val userCode: String = "",
    val starting: Boolean = false,
    val sessionId: String = "",
    val qrContent: String = "",
    val status: String = "",
    val devices: List<TuyaImportDevice> = emptyList(),
    val selected: Set<String> = emptySet(),
    val applying: Boolean = false,
    val imported: List<TuyaImportItem> = emptyList(),
    val failed: List<TuyaImportFailure> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class LightsViewModel @Inject constructor(
    private val repository: ValkyRepository,
    private val actionGate: MobileActionGate,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(LightsState())
    val state = _state.asStateFlow()
    private val _import = MutableStateFlow(TuyaImportState())
    val importState = _import.asStateFlow()
    private var importPoll: Job? = null
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
        realtime = repository.api.realtime({ message -> if (!repository.applyLightEvent(message)) refresh() }, { if (active) viewModelScope.launch { delay(5_000); if (active) connectRealtime() } }, eventPrefix = "light.")
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
        val subnet = wifiSubnet()
        if (subnet == null) {
            _state.update { it.copy(registrationError = context.getString(R.string.light_scan_wifi_required)) }
            return
        }
        _state.update { it.copy(scanning = true, registrationError = null) }
        viewModelScope.launch {
            runCatching { repository.discoverLights(subnet) }
                .onSuccess { found -> _state.update { it.copy(candidates = found, scanning = false) } }
                .onFailure { error -> _state.update { it.copy(scanning = false, registrationError = error.message) } }
        }
    }

    /** Abre a importação da Tuya; a sessão e a chave local ficam no servidor. */
    fun openImport() {
        importPoll?.cancel()
        importPoll = null
        _import.value = TuyaImportState(open = true)
    }

    fun closeImport() {
        val session = _import.value.sessionId
        importPoll?.cancel()
        importPoll = null
        _import.value = TuyaImportState()
        if (session.isNotBlank()) {
            viewModelScope.launch { runCatching { repository.cancelTuyaImport(session) } }
        }
    }

    fun setImportUserCode(value: String) = _import.update { it.copy(userCode = value, error = null) }

    fun startImport() {
        val current = _import.value
        if (current.starting || current.userCode.isBlank()) return
        val previous = current.sessionId
        _import.update {
            it.copy(
                starting = true, error = null, sessionId = "", qrContent = "", status = "",
                devices = emptyList(), selected = emptySet(), imported = emptyList(), failed = emptyList(),
            )
        }
        viewModelScope.launch {
            if (previous.isNotBlank()) runCatching { repository.cancelTuyaImport(previous) }
            runCatching { repository.startTuyaImport(current.userCode.trim(), wifiSubnet()) }
                .onSuccess { started ->
                    _import.update { it.copy(starting = false, sessionId = started.sessionId, qrContent = started.qrContent, status = "pending") }
                    pollImport(started.sessionId)
                }
                .onFailure { error ->
                    _import.update { it.copy(starting = false, error = error.message ?: context.getString(R.string.tuya_import_error_generic)) }
                }
        }
    }

    private fun pollImport(session: String) {
        importPoll?.cancel()
        importPoll = viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                val result = runCatching { repository.tuyaImportStatus(session) }
                if (result.isFailure) {
                    _import.update { it.copy(error = result.exceptionOrNull()?.message ?: context.getString(R.string.tuya_import_error_generic)) }
                    return@launch
                }
                val status = result.getOrThrow()
                val selectable = status.devices.filter { it.hasLocalKey && !it.sub }.map { it.deviceId }.toSet()
                _import.update {
                    it.copy(
                        status = status.status,
                        devices = status.devices,
                        selected = if (status.status == "authorized") selectable else it.selected,
                        error = status.error.ifBlank { null },
                    )
                }
                if (status.status != "pending") return@launch
            }
        }
    }

    fun toggleImportDevice(deviceId: String) = _import.update { current ->
        current.copy(selected = if (deviceId in current.selected) current.selected - deviceId else current.selected + deviceId)
    }

    fun applyImport() {
        val current = _import.value
        if (current.applying || current.selected.isEmpty() || current.sessionId.isBlank()) return
        _import.update { it.copy(applying = true, error = null, imported = emptyList(), failed = emptyList()) }
        viewModelScope.launch {
            runCatching { repository.applyTuyaImport(current.sessionId, current.selected.toList()) }
                .onSuccess { result ->
                    _import.update { it.copy(applying = false, imported = result.imported, failed = result.failed) }
                    runCatching { repository.refreshLights() }
                }
                .onFailure { error ->
                    _import.update { it.copy(applying = false, error = error.message ?: context.getString(R.string.tuya_import_error_generic)) }
                }
        }
    }

    /**
     * A sub-rede /24 do Wi-Fi atual. O backend roda em um container e não
     * recebe o broadcast da LAN; mandar a faixa da casa faz o "Buscar na rede"
     * funcionar mesmo atrás da bridge do Docker.
     */
    private fun wifiSubnet(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        val address = properties.linkAddresses.asSequence()
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        val bytes = address.address
        if (bytes.size != 4) return null
        return "${bytes[0].toInt() and 0xff}.${bytes[1].toInt() and 0xff}.${bytes[2].toInt() and 0xff}"
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
        importPoll?.cancel()
        realtime?.close(1000, "lights screen closed")
        super.onCleared()
    }
}
