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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Redes visíveis quando nenhuma se parece com uma lâmpada, para o usuário escolher. */
    val lampNetworks: List<String> = emptyList(),
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
        _state.update { it.copy(network = wifiSecrets.credentials()?.ssid ?: currentSsid(context).orEmpty()) }
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

    /** Senha já guardada no aparelho, para o formulário vir preenchido. */
    fun savedPassword(): String = wifiSecrets.credentials()?.password.orEmpty()

    /** Rede já guardada, quando houver. */
    fun savedNetwork(): String = wifiSecrets.credentials()?.ssid.orEmpty()

    /**
     * Configura uma lâmpada em modo de pareamento pelo caminho direto (modo AP):
     * o celular entra na rede que a lâmpada cria, entrega os dados da rede de
     * casa e volta. Não depende do roteador nem da faixa de 2,4 GHz.
     */
    fun configure(ssid: String, password: String, remember: Boolean = true, lampSsid: String? = null) {
        if (pairingJob?.isActive == true) return
        val network = ssid.trim()
        if (network.isEmpty()) {
            _state.update { it.copy(error = "Não identifiquei a rede do celular. Conecte o celular no Wi-Fi de casa e tente de novo.") }
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
            _state.update {
                it.copy(
                    network = network,
                    hasCredentials = true,
                    pairing = PairingProgress("lamp-network", "Procurando a rede da lâmpada…"),
                )
            }
            val connector = WifiApConnector(context)
            if (!connector.supported()) {
                _state.update {
                    it.copy(pairing = PairingProgress("failed", "Este aparelho usa uma versão do Android anterior à 10, que não permite a conexão direta com a lâmpada. Atualize o sistema ou use outro celular."))
                }
                return@launch
            }
            // Varredura nova antes de procurar: a lista guardada pode ser antiga.
            _state.update { it.copy(pairing = PairingProgress("lamp-network", "Procurando a rede da lâmpada…")) }
            connector.refreshScan()
            val candidates = withContext(Dispatchers.IO) { connector.candidateNetworks() }
            val visible = withContext(Dispatchers.IO) { connector.visibleNetworks() }
            val lamp = lampSsid?.takeIf { it.isNotBlank() } ?: candidates.firstOrNull()
            if (lamp == null) {
                // Nenhum nome conhecido: oferece as redes visíveis para escolha.
                _state.update {
                    it.copy(
                        lampNetworks = visible,
                        pairing = PairingProgress("choose-lamp", "Não reconheci a rede da lâmpada pelo nome. Escolha abaixo a rede que apareceu quando ela começou a piscar."),
                    )
                }
                return@launch
            }
            _state.update { it.copy(pairing = PairingProgress("lamp-network", "Entrando na rede “$lamp” da lâmpada — aceite o aviso do Android.")) }
            val connection = connector.connect(lamp)
            if (connection == null) {
                _state.update {
                    it.copy(pairing = PairingProgress("failed", "O celular não conseguiu entrar na rede da lâmpada. Tente de novo e aceite o aviso do Android."))
                }
                return@launch
            }
            try {
                _state.update { it.copy(pairing = PairingProgress("sending", "Conectado na lâmpada; entregando os dados do Wi-Fi de casa…")) }
                TuyaApConfig.deliverWithRetries(
                    network = connection.network,
                    ssid = network,
                    password = password,
                    appToken = session.token,
                    secret = session.secret,
                    region = session.region,
                ) { format ->
                    _state.update { state -> state.copy(pairing = PairingProgress("sending", "Entregando os dados do Wi-Fi ($format)…")) }
                }
                _state.update { it.copy(pairing = PairingProgress("waiting", "Dados entregues. A lâmpada vai entrar na rede de casa sozinha…")) }
            } finally {
                connection.close()
            }
            // De volta à rede de casa, acompanha o backend até adotar a lâmpada.
            while (isActive) {
                val status = runCatching { repository.lightPairingStatus(session.id) }.getOrNull()
                if (status != null) {
                    _state.update { it.copy(pairing = PairingProgress(status.status, status.message, status.address, status.light)) }
                    if (status.status == "paired") {
                        repository.refreshLights()
                        return@launch
                    }
                    if (status.status == "failed") return@launch
                }
                delay(1500)
            }
        }
    }

    /** Para de enviar, encerra a janela do servidor e limpa o resultado. */
    fun dismissPairing() {
        val sessionId = pairingSessionId
        pairingSessionId = null
        pairingJob?.cancel()
        pairingJob = null
        _state.update { it.copy(pairing = null) }
        if (sessionId != null) viewModelScope.launch { runCatching { repository.cancelLightPairing(sessionId) } }
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
