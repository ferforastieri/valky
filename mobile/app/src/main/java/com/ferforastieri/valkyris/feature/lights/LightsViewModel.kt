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
import com.ferforastieri.valkyris.core.model.LightPairingSession
import kotlin.coroutines.coroutineContext
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
    private var pendingSession: LightPairingSession? = null
    private var pendingLampSsid: String? = null
    private var pendingHomeSsid: String? = null
    private var pendingPassword: String? = null
    private var savedSsid: String? = null
    /** Último relato que ainda não chegou ao servidor (fica para reenviar). */
    private var diagnosticoPendente: Pair<String, String>? = null

    init {
        viewModelScope.launch { repository.lights.collect { value -> _state.update { it.copy(lights = value, loading = false) } } }
        refresh(); connectRealtime()
        viewModelScope.launch { while (isActive) { delay(20_000); runCatching { repository.refreshLights() } } }
        _state.update { it.copy(network = wifiSecrets.credentials()?.ssid ?: currentSsid(context).orEmpty()) }
    }

    private fun connectRealtime() {
        realtime = repository.api.realtime({ refresh() }, { if (active) viewModelScope.launch { delay(5_000); if (active) connectRealtime() } }, eventPrefix = "light.")
    }

    fun refresh() = viewModelScope.launch { reenviarDiagnostico(); runCatching { repository.refreshLights() }.onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } } }
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

    /** Se o app tem a permissão de Localização (a varredura de redes depende dela). */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Permissões que o pareamento precisa (Localização e, no Android 13+, Wi-Fi por perto). */
    fun hasPairingPermissions(): Boolean {
        if (!hasLocationPermission()) return false
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    /** O interruptor de Localização do celular está ligado? */
    fun locationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * Relata ao servidor e INSISTE até conseguir: depois do passo 2 o celular
     * volta sozinho para a rede de casa e o relato sobe em segundos, sem o
     * usuário precisar mexer em nada.
     */
    private fun relatar(sessionId: String, detalhe: String) {
        diagnosticoPendente = sessionId to detalhe
        viewModelScope.launch {
            repeat(40) {
                val pendente = diagnosticoPendente ?: return@launch
                val enviado = runCatching {
                    repository.api.reportLightPairingDiagnostic(pendente.first, pendente.second)
                }.isSuccess
                if (enviado) {
                    if (diagnosticoPendente == pendente) diagnosticoPendente = null
                    return@launch
                }
                delay(3_000)
            }
        }
    }

    /** Tenta de novo o último relato (chamado no refresh, quando a rede voltou). */
    private fun reenviarDiagnostico() {
        val pendente = diagnosticoPendente ?: return
        viewModelScope.launch {
            runCatching { repository.api.reportLightPairingDiagnostic(pendente.first, pendente.second) }
                .onSuccess { if (diagnosticoPendente == pendente) diagnosticoPendente = null }
        }
    }

    /** Aviso na folha quando falta permissão ou a Localização está desligada. */
    fun pairingNotice(message: String) {
        if (pairingJob?.isActive == true) return
        _state.update { it.copy(pairing = PairingProgress("failed", message)) }
    }

    /**
     * Configura uma lâmpada em modo de pareamento pelo caminho direto (modo AP):
     * o celular entra na rede que a lâmpada cria, entrega os dados da rede de
     * casa e volta. Não depende do roteador nem da faixa de 2,4 GHz.
     */
    fun configure(ssid: String, password: String, remember: Boolean = true, lampSsid: String? = null) {
        // Um toque novo sempre vale: cancela o que estiver em andamento (a
        // lista que se atualiza sozinha segura o trabalho antigo).
        pairingJob?.cancel()
        pairingJob = null
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
            pendingSession = session
            pendingHomeSsid = network
            pendingPassword = password
            try {
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
            var candidates = emptyList<String>()
            var visible = emptyList<String>()
            repeat(4) { round ->
                connector.refreshScan(if (round == 0) 9_000 else 6_000)
                candidates = withContext(Dispatchers.IO) { connector.candidateNetworks() }
                visible = withContext(Dispatchers.IO) { connector.visibleNetworks() }
                if (candidates.isNotEmpty() || visible.isNotEmpty()) return@repeat
                delay(2_500)
            }
            val lamp = lampSsid?.takeIf { it.isNotBlank() } ?: candidates.firstOrNull()
            if (lamp == null) {
                // Nenhum nome conhecido: oferece as redes visíveis para escolha.
                val message = if (visible.isEmpty()) {
                    "Ainda não vi nenhuma rede. Confirme que a lâmpada está piscando devagar e que a Localização do celular está ligada."
                } else {
                    "Escolha a rede que a lâmpada criou:"
                }
                _state.update { it.copy(lampNetworks = visible, pairing = PairingProgress("choose-lamp", message)) }
                // A lista se atualiza sozinha: a lâmpada costuma aparecer
                // alguns segundos depois, quando o sistema varre de novo.
                repeat(20) {
                    delay(7_000)
                    if (_state.value.pairing?.phase != "choose-lamp") return@launch
                    connector.refreshScan(6_000)
                    val frescas = withContext(Dispatchers.IO) { connector.visibleNetworks() }
                    val achadas = withContext(Dispatchers.IO) { connector.candidateNetworks() }
                    if (frescas.isNotEmpty()) {
                        val aviso = if (achadas.isNotEmpty()) "Achei a rede “${achadas.first()}” — toque nela para continuar:" else "Escolha a rede que a lâmpada criou:"
                        _state.update { it.copy(lampNetworks = frescas, pairing = PairingProgress("choose-lamp", aviso)) }
                    }
                }
                return@launch
            }
            _state.update { it.copy(pairing = PairingProgress("lamp-network", "Entrando na rede “$lamp” da lâmpada — aceite o aviso do Android.")) }
            val connection = connector.connect(lamp) ?: connector.connect(lamp)
            if (connection == null) {
                // Caminho garantido quando a entrada automática é recusada: o
                // usuário conecta pelo Wi-Fi do sistema e volta; o app detecta
                // a rede e continua daqui (continueManual).
                pendingSession = session
                pendingLampSsid = lamp
                pendingHomeSsid = network
                pendingPassword = password
                relatar(session.id, "não entrei sozinho na rede $lamp; aguardando conexão manual")
                _state.update {
                    it.copy(pairing = PairingProgress("manual-wifi", "Não consegui entrar sozinho na rede “$lamp”. Toque em Abrir Wi-Fi, conecte nela e volte para cá — eu continuo daqui."))
                }
                return@launch
            }
            deliverAndWait(session, connection.network, network, password, connector.gatewayAddresses()) { connection.close() }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Qualquer falha inesperada vira aviso na folha, nunca um fechamento.
                _state.update {
                    it.copy(pairing = PairingProgress("failed", error.message ?: "Não foi possível falar com a lâmpada. Tente de novo."))
                }
            }
        }
    }

    /**
     * Entrega os dados pela rede da lâmpada e acompanha o backend até ela ser
     * adotada. Serve tanto para a entrada automática quanto para quando o
     * usuário conecta pelo Wi-Fi do sistema.
     */
    private suspend fun deliverAndWait(
        session: LightPairingSession,
        link: android.net.Network,
        homeSsid: String,
        password: String,
        gateways: List<String>,
        closeLink: (() -> Unit)? = null,
    ) {
        _state.update { it.copy(pairing = PairingProgress("sending", "Conectado na lâmpada; entregando os dados do Wi-Fi de casa…")) }
        var resultado = TuyaApConfig.Resultado(false, false, "sem resposta")
        try {
            resultado = TuyaApConfig.deliverWithRetries(
                network = link,
                ssid = homeSsid,
                password = password,
                appToken = session.token,
                secret = session.secret,
                region = session.region,
                gateways = gateways,
            ) { format ->
                _state.update { state -> state.copy(pairing = PairingProgress("sending", "Entregando os dados do Wi-Fi ($format)…")) }
            }
        } finally {
            closeLink?.invoke()
        }
        relatar(session.id, resultado.detalhe)
        if (!resultado.enviado) {
            _state.update {
                it.copy(pairing = PairingProgress("failed", "A lâmpada não aceitou os dados (${resultado.detalhe}). Deixe-a piscando devagar e tente de novo."))
            }
            return
        }
        _state.update {
            val texto = if (resultado.confirmado) {
                "Dados aceitos! Pode voltar o celular para o Wi-Fi de casa — eu envio o relatório e confirmo sozinho."
            } else {
                "Dados enviados! Pode voltar o celular para o Wi-Fi de casa — eu envio o relatório e confirmo sozinho."
            }
            it.copy(pairing = PairingProgress("waiting", texto))
        }
        // Espera limitada: se a lâmpada não aparecer em 4 minutos, avisa.
        val limite = System.currentTimeMillis() + 4 * 60 * 1000
        while (coroutineContext.isActive && System.currentTimeMillis() < limite) {
            val status = runCatching { repository.lightPairingStatus(session.id) }.getOrNull()
            if (status != null) {
                _state.update { it.copy(pairing = PairingProgress(status.status, status.message, status.address, status.light)) }
                if (status.status == "paired") {
                    repository.refreshLights()
                    return
                }
                if (status.status == "failed") return
            }
            delay(1500)
        }
        if (coroutineContext.isActive) {
            _state.update {
                it.copy(pairing = PairingProgress("failed", "A lâmpada não apareceu na rede de casa. Deixe-a piscando devagar e tente de novo."))
            }
        }
    }

    /** Continua quando o usuário conecta na rede da lâmpada pelo Wi-Fi do sistema. */
    fun continueManual() {
        val progress = _state.value.pairing ?: return
        if (progress.phase != "manual-wifi" && progress.phase != "choose-lamp") return
        val session = pendingSession ?: return
        val home = pendingHomeSsid ?: return
        val password = pendingPassword ?: return
        val connector = WifiApConnector(context)
        val ssid = currentSsid(context) ?: return
        val lamp = pendingLampSsid
        val connected = (lamp != null && ssid.equals(lamp, ignoreCase = true)) || connector.looksLikeLamp(ssid)
        if (!connected) return
        // O trabalho antigo (a lista que se atualiza) sai de cena.
        pairingJob?.cancel()
        pairingJob = null
        pendingSession = null
        pendingLampSsid = null
        pendingHomeSsid = null
        pendingPassword = null
        pairingJob = viewModelScope.launch {
            // Segura o celular na rede da lâmpada durante toda a entrega: sem
            // isso o Android volta sozinho para a rede com internet no meio.
            val presa = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { connector.connect(ssid) }.getOrNull() else null
            try {
                val link = presa?.network ?: connector.wifiNetwork() ?: return@launch
                runCatching { deliverAndWait(session, link, home, password, connector.gatewayAddresses()) }
                    .onFailure { error -> _state.update { it.copy(pairing = PairingProgress("failed", error.message ?: "Não foi possível entregar os dados. Tente de novo.")) } }
            } finally {
                presa?.close()
            }
        }
    }

    /** Ao sair da tela: mantém o pareamento vivo enquanto ele estiver em andamento. */
    fun pausePairing() {
        if (_state.value.pairing?.phase == "failed") dismissPairing()
    }

    /** Para de enviar, encerra a janela do servidor e limpa o resultado. */
    fun dismissPairing() {
        val sessionId = pairingSessionId
        pairingSessionId = null
        pendingSession = null
        pendingLampSsid = null
        pendingHomeSsid = null
        pendingPassword = null
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
