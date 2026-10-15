package com.ferforastieri.valkyris.feature.events

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.ValkyrisEvent
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    val events = repository.events
    private var realtime: okhttp3.WebSocket? = null
    // Só conversa com o servidor enquanto as notificações estão à vista.
    private var active = false

    init {
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
        realtime = repository.api.realtime({ _ -> refresh() }, {
            if (active) viewModelScope.launch { delay(5_000); if (active) { connectRealtime(); refresh() } }
        }, eventPrefix = "event")
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repository.refreshEvents() } }
    }

    fun acknowledge(event: ValkyrisEvent) {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { repository.acknowledge(event.id) }
                runCatching { repository.refreshEvents() }
            } finally {
                actionGate.release()
            }
        }
    }

    /** Marca tudo como visto ao abrir o aplicativo; nada de tocar em check. */
    fun markAllSeen() {
        if (events.value.none { it.acknowledgedAt == null }) return
        acknowledgeAll()
    }

    fun acknowledgeAll() {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { repository.acknowledgeAll() }
                runCatching { repository.refreshEvents() }
            } finally {
                actionGate.release()
            }
        }
    }

    override fun onCleared() {
        active = false
        realtime?.close(1000, "screen closed")
        super.onCleared()
    }
}

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
    saved: SavedStateHandle,
) : ViewModel() {
    private val api: ValkyrisApi = repository.api
    val id: String = checkNotNull(saved["id"])
    private val _event = MutableStateFlow<ValkyrisEvent?>(null)
    val event = _event.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                val value = runCatching { api.event(id) }.getOrNull()
                if (value != null) {
                    _event.value = value
                    // A clip may be intentionally absent, or its preparation can
                    // fail. Only a pending recording needs polling.
                    if (value.clipStatus != "processing") break
                }
                delay(2_000)
            }
        }
    }

    fun acknowledge() {
        if (!actionGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                runCatching { repository.acknowledge(id) }
                _event.value = runCatching { api.event(id) }.getOrNull() ?: _event.value
            } finally {
                actionGate.release()
            }
        }
    }

    fun clipUrl() = api.clipUrl(id)
    fun token() = api.token()
    fun httpClient() = api.mediaHttpClient()
}
