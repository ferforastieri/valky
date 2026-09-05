package com.ferforastieri.valkyris.feature.events

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.database.EventEntity
import com.ferforastieri.valkyris.core.model.ValkyrisEvent
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    val events = repository.cachedEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var realtime: okhttp3.WebSocket? = null
    private var active = true

    init {
        refresh()
        connectRealtime()
    }

    private fun connectRealtime() {
        realtime = repository.api.realtime({ refresh() }) {
            if (active) viewModelScope.launch { delay(5_000); if (active) { connectRealtime(); refresh() } }
        }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repository.refreshEvents() } }
    }

    fun acknowledge(event: EventEntity) {
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
    private val _snapshot = MutableStateFlow<Bitmap?>(null)
    val snapshot = _snapshot.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { api.event(id) }.onSuccess { value ->
                _event.value = value
                if (value.snapshotPath != null) loadSnapshot()
            }
        }
    }

    private fun loadSnapshot() {
        viewModelScope.launch {
            _snapshot.value = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(api.eventSnapshotUrl(id)).header("Authorization", "Bearer ${api.token()}").build()
                    api.mediaHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val bytes = response.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
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
