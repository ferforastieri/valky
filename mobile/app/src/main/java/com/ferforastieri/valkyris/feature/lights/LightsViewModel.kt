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
     * Deixa o que o aplicativo viu no diagnóstico da sessão, no banco, e
     * INSISTE até conseguir: depois do passo 2 o celular volta sozinho para a
     * rede de casa e o relato sobe em segundos, sem o usuário mexer em nada.
     */
    private fun relatar(sessionId: String, detalhe: String) {
        diagnosticoPendente = sessionId to detalhe
        viewModelScope.launch {
            repeat(40) {
                val pendente = diagnosticoPendente ?: return@launch
                val enviado = runCatching {
                    repository.lightPairingStatus(pendente.first, pendente.second)
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
            runCatching { repository.lightPairingStatus(pendente.first, pendente.second) }
                .onSuccess { if (diagnosticoPendente == pendente) diagnosticoPendente = null }
        }
    }

    /** Aviso na folha quando falta permissão ou a Localização está desligada. */
    fun pairingNotice(message: String) {
        if (pairingJob?.isActive == true) return
        _state.update { it.copy(pairing = PairingProgress("failed", message)) }
    }

    /**
     * Pareamento direto (modo AP), na ordem escolhida na folha:
     * 1) o celular entra na rede escolhida pelo usuário — se a senha estiver
     *    errada, é aqui que descobrimos, antes de envolver a lâmpada;
     * 2) o app procura a rede da lâmpada sozinho (sem lista);
     * 3) o app abre o Wi-Fi do sistema e o usuário conecta na lâmpada;
     * 4) o app entrega exatamente a rede e a senha escolhidas no passo 1.
     */
    fun configurar(redeCasa: String, senhaCasa: String) {
        pairingJob?.cancel()
        pairingJob = null
        val rede = redeCasa.trim()
        if (rede.isEmpty()) {
            _state.update { it.copy(error = "Escolha a rede que a lâmpada vai usar.") }
            return
        }
        if (senhaCasa.isBlank()) {
            _state.update { it.copy(error = "Informe a senha do Wi-Fi.") }
            return
        }
        if (WifiApConnector(context).looksLikeLamp(rede)) {
            _state.update { it.copy(error = "“$rede” é a rede da própria lâmpada. Escolha a rede da sua casa.") }
            return
        }
        wifiSecrets.save(rede, senhaCasa)
        pairingJob = viewModelScope.launch {
            val session = runCatching { repository.startLightPairing(rede) }
                .getOrElse { error ->
                    _state.update { it.copy(pairing = PairingProgress("failed", error.message ?: "Não foi possível abrir o pareamento.")) }
                    return@launch
                }
            pairingSessionId = session.id
            pendingSession = session
            pendingHomeSsid = rede
            pendingPassword = senhaCasa
            try {
                val connector = WifiApConnector(context)
                if (!connector.supported()) {
                    _state.update {
                        it.copy(pairing = PairingProgress("failed", "Este aparelho usa uma versão do Android anterior à 10, que não permite a conexão direta com a lâmpada. Atualize o sistema ou use outro celular."))
                    }
                    return@launch
                }
                // 1) O celular entra na rede escolhida (quando já está nela, nada acontece).
                val jaNela = currentSsid(context).orEmpty().equals(rede, ignoreCase = true)
                if (!jaNela) {
                    _state.update {
                        it.copy(
                            network = rede,
                            hasCredentials = true,
                            pairing = PairingProgress("home-network", "Entrando na rede “$rede” — aceite o aviso do Android."),
                        )
                    }
                    val casa = connector.connect(rede, senhaCasa)
                    if (casa == null) {
                        _state.update {
                            it.copy(pairing = PairingProgress("failed", "Não consegui entrar na rede “$rede”. Confira a senha e tente de novo."))
                        }
                        return@launch
                    }
                    casa.close()
                }
                _state.update { it.copy(network = rede, hasCredentials = true) }
                // 2) Procura a rede da lâmpada sozinho.
                _state.update { it.copy(pairing = PairingProgress("lamp-network", "Procurando a rede da lâmpada…")) }
                var lamp: String? = null
                repeat(4) { round ->
                    connector.refreshScan(if (round == 0) 9_000 else 6_000)
                    lamp = withContext(Dispatchers.IO) { connector.candidateNetworks() }.firstOrNull()
                    if (lamp != null) return@repeat
                    delay(2_000)
                }
                // 3) O usuário conecta na lâmpada pelo Wi-Fi do sistema (a tela
                // abre sozinha) e o app continua no continueManual.
                pendingLampSsid = lamp
                val onde = lamp?.let { "na rede “$it”" } ?: "na rede da lâmpada"
                relatar(session.id, "aguardando conexão manual $onde")
                _state.update {
                    it.copy(pairing = PairingProgress("manual-wifi", "Conecte o celular $onde e volte para cá — eu continuo sozinho."))
                }
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
                reg = session.reg,
                gateways = gateways,
            ) { aviso ->
                _state.update { state ->
                    val texto = when {
                        aviso.startsWith("pareamento") -> "Entregando os dados do Wi-Fi…"
                        // Aceite é só o começo: ela agora vai entrar na rede e isso
                        // leva alguns minutos. A folha mostra isso desde já, sem
                        // parecer parada esperando um veredito que não vem.
                        aviso.startsWith("0 ") -> null
                        else -> "A lâmpada reportou: $aviso"
                    }
                    if (texto == null) {
                        state.copy(pairing = PairingProgress("waiting", ACEITOU_AVISO))
                    } else {
                        state.copy(pairing = PairingProgress("sending", texto))
                    }
                }
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
        // Veredito dela sobre a entrada na rede: 2 = não achou a rede,
        // 3 = senha recusada, 4 = não conseguiu entrar, 5 = sem endereço,
        // 6/7 = servidor, 8 = ativação falhou, 9 = ativada.
        val veredito = resultado.veredito
        if (veredito != null && veredito != 9) {
            _state.update {
                it.copy(pairing = PairingProgress("failed", "A lâmpada reportou: ${TuyaApTlsPsk.nomeDoCodigo(veredito)}. Deixe-a piscando devagar e tente de novo."))
            }
            return
        }
        _state.update {
            val texto = when {
                veredito == 9 -> "Ativada! Ela entrou na rede e pegou a chave. Pode voltar o celular para o Wi-Fi de casa."
                resultado.confirmado -> ACEITOU_AVISO
                else -> "Dados enviados! Pode voltar o celular para o Wi-Fi de casa — eu envio o relatório e confirmo sozinho."
            }
            it.copy(pairing = PairingProgress("waiting", texto))
        }
        // Espera limitada: ela leva alguns minutos para entrar na rede; se não
        // aparecer até o fim da janela, a folha diz o que fazer.
        val limite = System.currentTimeMillis() + 12 * 60 * 1000
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
                it.copy(pairing = PairingProgress("failed", "A lâmpada não apareceu na rede de casa. Deixe-a ligada por alguns minutos e abra o + de novo — o servidor a encontra na hora."))
            }
        }
    }

    /** Continua quando o usuário conecta na rede da lâmpada pelo Wi-Fi do sistema. */
    fun continueManual() {
        val progress = _state.value.pairing ?: return
        if (progress.phase != "manual-wifi") return
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

/**
 * Depois do aceite a lâmpada leva alguns minutos para entrar na rede de casa.
 * A folha mostra isso desde o primeiro instante, sem parecer travada esperando
 * um veredito que o canal dela não entrega mais.
 */
private const val ACEITOU_AVISO = "Ela aceitou os dados! Agora está entrando na rede de casa — isso leva alguns minutos. Pode voltar o celular para o Wi-Fi de casa; eu acompanho sozinho."

/** Nome da rede Wi-Fi atual, quando o sistema permite ler. */
internal fun currentSsid(context: Context): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    val raw = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
    val name = raw.trim().removeSurrounding("\"")
    if (name.isEmpty() || TextUtils.equals(name, WifiManager.UNKNOWN_SSID)) return null
    return name
}
