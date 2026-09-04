package com.ferforastieri.valkyris.feature.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.core.model.PTZCommand
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject

data class CamerasState(
    val loading: Boolean = true,
    val creating: Boolean = false,
    val deleting: Set<String> = emptySet(),
    val cameras: List<Camera> = emptyList(),
    val snapshots: Map<String, Bitmap> = emptyMap(),
    val error: String? = null,
)

@HiltViewModel
class CamerasViewModel @Inject constructor(private val api: ValkyrisApi) : ViewModel() {
    private val _state = MutableStateFlow(CamerasState())
    val state = _state.asStateFlow()
    private val mediaClient by lazy { api.mediaHttpClient() }
    private var realtime: okhttp3.WebSocket? = null
    private var active = true

    init {
        refresh()
        connectRealtime()
        viewModelScope.launch {
            var cycles = 0
            while (isActive) {
                delay(STATUS_REFRESH_MS)
                runCatching { api.cameras() }.onSuccess { cameras ->
                    _state.value = _state.value.copy(cameras = cameras, loading = false)
                }
                cycles++
                if (cycles % SNAPSHOT_REFRESH_CYCLES == 0) refreshSnapshots()
            }
        }
    }

    private fun connectRealtime() {
        realtime = api.realtime({ refreshStatuses() }) {
            if (active) viewModelScope.launch { delay(5_000); if (active) connectRealtime() }
        }
    }

    private fun refreshStatuses() {
        viewModelScope.launch {
            runCatching { api.cameras() }.onSuccess { cameras ->
                _state.value = _state.value.copy(cameras = cameras, loading = false)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val current = _state.value
            _state.value = current.copy(loading = true, error = null)
            runCatching { api.cameras() }
                .onSuccess {
                    _state.value = current.copy(loading = false, cameras = it)
                    refreshSnapshots()
                }
                .onFailure { _state.value = current.copy(loading = false, error = it.message) }
        }
    }

    private suspend fun refreshSnapshots() {
        val cameras = _state.value.cameras
        if (cameras.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) {
            cameras.mapNotNull { camera ->
                runCatching {
                    val request = Request.Builder()
                        .url(api.snapshotUrl(camera.id))
                        .header("Authorization", "Bearer ${api.token()}")
                        .build()
                    mediaClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val bytes = response.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { camera.id to it }
                    }
                }.getOrNull()
            }.toMap()
        }
        if (loaded.isNotEmpty()) {
            _state.value = _state.value.copy(snapshots = _state.value.snapshots + loaded)
        }
    }

    fun add(input: CreateCameraRequest) {
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, error = null)
            runCatching { api.createCamera(input) }
                .onSuccess { camera ->
                    _state.value = _state.value.copy(
                        creating = false,
                        cameras = (_state.value.cameras + camera).distinctBy { it.id },
                    )
                }
                .onFailure { _state.value = _state.value.copy(creating = false, error = it.message) }
        }
    }

    fun delete(id: String) {
        if (id in _state.value.deleting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(deleting = _state.value.deleting + id, error = null)
            runCatching { api.deleteCamera(id) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        deleting = _state.value.deleting - id,
                        cameras = _state.value.cameras.filterNot { it.id == id },
                        snapshots = _state.value.snapshots - id,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        deleting = _state.value.deleting - id,
                        error = it.message,
                    )
                }
        }
    }

    private companion object {
        const val STATUS_REFRESH_MS = 2_000L
        const val SNAPSHOT_REFRESH_CYCLES = 8
    }

    override fun onCleared() {
        active = false
        realtime?.close(1000, "camera screen closed")
        super.onCleared()
    }
}
@HiltViewModel
class CameraLiveViewModel @Inject constructor(private val api: ValkyrisApi, saved: SavedStateHandle) : ViewModel() {
    val id: String = checkNotNull(saved["id"])
    private val _camera = MutableStateFlow<Camera?>(null)
    val camera = _camera.asStateFlow()
    private var realtime: okhttp3.WebSocket? = null
    init {
        realtime = api.realtime({ refresh() })
        viewModelScope.launch {
            while (isActive) {
                runCatching { api.cameras().firstOrNull { it.id == id } }.onSuccess { _camera.value = it }
                if (_camera.value?.setupStatus in setOf("ready", "failed")) break
                delay(1_000)
            }
        }
    }
    private fun refresh() {
        viewModelScope.launch { runCatching { api.cameras().firstOrNull { it.id == id } }.onSuccess { _camera.value = it } }
    }
    fun move(pan: Double, tilt: Double, zoom: Double = 0.0) { viewModelScope.launch { runCatching { api.ptz(id, PTZCommand("move", pan, tilt, zoom)) } } }
    fun stop() { viewModelScope.launch { runCatching { api.ptz(id, PTZCommand("stop")) } } }
    fun liveUrl() = api.liveUrl(id)
    fun token() = api.token()
    fun httpClient() = api.mediaHttpClient()
    override fun onCleared() {
        realtime?.close(1000, "camera detail closed")
        super.onCleared()
    }
}
