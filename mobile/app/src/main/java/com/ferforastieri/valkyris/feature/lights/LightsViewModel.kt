package com.ferforastieri.valkyris.feature.lights

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.LightDevice
import com.ferforastieri.valkyris.core.model.LightStatePatch
import com.ferforastieri.valkyris.core.model.LightUpdate
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import com.ferforastieri.valkyris.core.security.WifiSecretStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** O que a tela mostra enquanto a lâmpada é configurada. */
data class PairingProgress(
    val phase: String,
    val message: String,
    val deviceAddress: String = "",
    val light: LightDevice? = null,
)

data class LightsState(
    val loading: Boolean = true,
    val busy: Set<String> = emptySet(),
    val lights: List<LightDevice> = emptyList(),
    val error: String? = null,
    val pairing: PairingProgress? = null,
    /** Rede usada no pareamento; vem da última vez ou do Wi-Fi atual. */
    val network: String = "",
    /** Quando falso, a tela precisa da senha do Wi-Fi uma única vez. */
    val hasCredentials: Boolean = false,
)

@HiltViewModel
class LightsViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
    private val wifiSecrets: WifiSecretStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(LightsState())
    val state = _state.asStateFlow()
    private var realtime: okhttp3.WebSocket? = null
    private var active = true
    private var pairingJob: Job? = null
    private var pairingSessionId: String? = null
    private var savedSsid: String? = null

    init {
        viewModelScope.launch { repository.lights.collect { value -> _state.update { it.copy(lights = value, loading = false) } } }
        refresh(); connectRealtime()
        viewModelScope.launch { while (isActive) { delay(20_000); runCatching { repository.refreshLights() } } }
        _state.update {
            it.copy(
                network = wifiSecrets.credentials()?.ssid ?: currentSsid(context).orEmpty(),
                hasCredentials = wifiSecrets.credentials() != null,
            )
        }
    }

    private fun connectRealtime() {
        realtime = repository.api.realtime({ refresh() }, { if (active) viewModelScope.launch { delay(5_000); if (active) connectRealtime() } }, eventPrefix = "light.")
    }

    fun refresh() = viewModelScope.launch { runCatching { repository.refreshLights() }.onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } } }
    fun control(id: String, patch: LightStatePatch) = guarded(id, false) { repository.controlLight(id, patch) }
    fun toggleFavorite(id: String, favorite: Boolean) = viewModelScope.launch { runCatching { repository.api.setFavorite("light", id, favorite) }.onSuccess { repository.refreshLights() }.onFailure { error -> _state.update { it.copy(error = error.message) } } }
    fun delete(id: String, done: () -> Unit = {}) = guarded(id) { repository.deleteLight(id); done() }

    fun rename(id: String, name: String, room: String, done: (Boolean) -> Unit = {}) {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { repository.updateLight(id, LightUpdate(name = name, room = room)) }
                    .onSuccess { done(true) }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            } finally {
                actionGate.release()
            }
        }
    }

    /** Nome da rede atual, quando o sistema permite ler (precisa de localização). */
    fun suggestedSsid(): String = _state.value.network.ifBlank { savedSsid ?: currentSsid(context).also { savedSsid = it }.orEmpty() }

    /**
     * A tela chama isto ao aparecer: se a rede já foi informada alguma vez, a
     * busca e o envio começam sozinhos e a lâmpada aparece na lista sem nenhum
     * toque. Sem a senha guardada, a tela pede uma única vez.
     */
    fun watch() {
        if (pairingJob?.isActive == true) return
        val credentials = wifiSecrets.credentials()
        if (credentials == null) {
            _state.update { it.copy(pairing = null, hasCredentials = false, network = it.network.ifBlank { currentSsid(context).orEmpty() }) }
            return
        }
        configure(credentials.ssid, credentials.password, remember = false)
    }

    /**
     * Configura uma lâmpada em modo de pareamento: o backend abre a janela, o
     * celular envia o Wi-Fi pelo ar e a janela acompanha o que aparecer na rede.
     * A senha fica guardada no aparelho para os próximos pareamentos.
     */
    fun configure(ssid: String, password: String, remember: Boolean = true) {
        if (pairingJob?.isActive == true) return
        val network = ssid.trim()
        if (network.isEmpty()) {
            _state.update { it.copy(error = "Informe o nome da rede Wi-Fi.") }
            return
        }
        if (password.isBlank()) {
            _state.update { it.copy(error = "Informe a senha do Wi-Fi.") }
            return
        }
        if (remember) wifiSecrets.save(network, password)
        pairingJob = viewModelScope.launch {
            val session = runCatching { repository.startLightPairing() }
                .getOrElse { error ->
                    _state.update { it.copy(pairing = PairingProgress("failed", error.message ?: "Não foi possível abrir o pareamento.")) }
                    return@launch
                }
            pairingSessionId = session.id
            _state.update { it.copy(network = network, hasCredentials = true, pairing = PairingProgress("sending", "Enviando os dados do Wi-Fi para a lâmpada…")) }
            val tokenGroup = TuyaSmartConfig.tokenGroup(session.region, session.token, session.secret)
            val sender = LocalPairingSender(context)
            // O envio acompanha a janela inteira: a lâmpada pode ser resetada
            // alguns minutos depois de a tela abrir.
            val send = launch {
                runCatching { sender.send(network, password, tokenGroup) }
                    .onFailure { error -> _state.update { it.copy(error = error.message) } }
            }
            try {
                while (isActive) {
                    val status = runCatching { repository.lightPairingStatus(session.id) }.getOrNull() ?: break
                    _state.update { it.copy(pairing = PairingProgress(status.status, status.message, status.address, status.light)) }
                    when (status.status) {
                        "paired" -> { repository.refreshLights(); return@launch }
                        "failed" -> return@launch
                    }
                    delay(1500)
                }
            } finally {
                send.cancelAndJoin()
            }
        }
    }

    /** Para de enviar e encerra a janela do servidor ao sair da tela. */
    fun stopWatching() {
        val sessionId = pairingSessionId
        pairingSessionId = null
        pairingJob?.cancel()
        pairingJob = null
        if (sessionId != null) viewModelScope.launch { runCatching { repository.cancelLightPairing(sessionId) } }
    }

    fun dismissPairingResult() {
        _state.update { it.copy(pairing = null) }
    }

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

/** Nome da rede Wi-Fi atual, quando o sistema permite ler. */
internal fun currentSsid(context: Context): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val raw = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
    val name = raw.trim().removeSurrounding("\"")
    if (name.isEmpty() || TextUtils.equals(name, WifiManager.UNKNOWN_SSID)) return null
    return name
}
