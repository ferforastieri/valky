package com.ferforastieri.valkyris.feature.cameras

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.core.model.CameraPreset
import com.ferforastieri.valkyris.core.model.PTZCommand
import com.ferforastieri.valkyris.core.media.DeviceMediaStore
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import com.ferforastieri.valkyris.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
class CamerasViewModel @Inject constructor(private val repository: ValkyrisRepository) : ViewModel() {
    private val api = repository.api
    private val _state = MutableStateFlow(CamerasState())
    val state = _state.asStateFlow()
    private val mediaClient by lazy { api.mediaHttpClient() }
    private var realtime: okhttp3.WebSocket? = null
    private var active = true

    init {
        viewModelScope.launch {
            repository.cameras.collect { cameras ->
                _state.update { state ->
                    state.copy(
                        cameras = cameras,
                        snapshots = state.snapshots.filterKeys { id -> cameras.any { it.id == id } },
                        loading = false,
                    )
                }
            }
        }
        refresh()
        connectRealtime()
        viewModelScope.launch {
            var cycles = 0
            while (isActive) {
                delay(STATUS_REFRESH_MS)
                runCatching { repository.refreshCameras() }
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
            runCatching { repository.refreshCameras() }
                .onSuccess { refreshSnapshots() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.cameras.isEmpty(), error = null) }
            runCatching { repository.refreshCameras() }
                .onSuccess { refreshSnapshots() }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    private suspend fun refreshSnapshots() {
        val cameras = _state.value.cameras.filter { it.setupStatus == "ready" }
        if (cameras.isEmpty()) {
            _state.update { it.copy(snapshots = emptyMap()) }
            return
        }
        val loaded = supervisorScope {
            cameras.map { camera -> async(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url(api.snapshotUrl(camera.id))
                        .header("Authorization", "Bearer ${api.token()}").build()
                    mediaClient.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }.execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val bytes = response.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { camera.id to it }
                    }
                }.getOrNull()
            } }.awaitAll().filterNotNull().toMap()
        }
        if (loaded.isNotEmpty()) {
            _state.update { state -> state.copy(snapshots = state.snapshots + loaded) }
        }
    }

    fun add(input: CreateCameraRequest) {
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            runCatching { repository.createCamera(input) }
                .onSuccess { _state.update { it.copy(creating = false) } }
                .onFailure { error -> _state.update { it.copy(creating = false, error = error.message) } }
        }
    }

    fun delete(id: String) {
        if (id in _state.value.deleting) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = it.deleting + id, error = null) }
            runCatching { repository.deleteCamera(id) }
                .onSuccess {
                    _state.update { it.copy(deleting = it.deleting - id, snapshots = it.snapshots - id) }
                }
                .onFailure { error ->
                    _state.update { it.copy(deleting = it.deleting - id, error = error.message) }
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
class CameraLiveViewModel @Inject constructor(
    private val api: ValkyrisApi,
    private val deviceMedia: DeviceMediaStore,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: String = checkNotNull(saved["id"])
    private val _camera = MutableStateFlow<Camera?>(null)
    val camera = _camera.asStateFlow()
    private val _snapshotLoading = MutableStateFlow(false)
    val snapshotLoading = _snapshotLoading.asStateFlow()
    private val _recordingLoading = MutableStateFlow(false)
    val recordingLoading = _recordingLoading.asStateFlow()
    private val _presets = MutableStateFlow<List<CameraPreset>>(emptyList())
    val presets = _presets.asStateFlow()
    private var realtime: okhttp3.WebSocket? = null
    init {
        realtime = api.realtime({ refresh() })
        viewModelScope.launch {
            while (isActive) {
                runCatching { api.cameras().firstOrNull { it.id == id } }.onSuccess { _camera.value = it }
                if (_camera.value?.setupStatus in setOf("ready", "failed")) {
                    if (_camera.value?.capabilities?.presets == true) loadPresets()
                    break
                }
                delay(1_000)
            }
        }
    }
    private fun refresh() {
        viewModelScope.launch { runCatching { api.cameras().firstOrNull { it.id == id } }.onSuccess { _camera.value = it } }
    }
    fun move(pan: Double, tilt: Double, zoom: Double = 0.0) { viewModelScope.launch { runCatching { api.ptz(id, PTZCommand("move", pan, tilt, zoom)) } } }
    fun stop() { viewModelScope.launch { runCatching { api.ptz(id, PTZCommand("stop")) } } }
    fun goToPreset(token: String) { viewModelScope.launch { runCatching { api.ptz(id, PTZCommand("preset", presetToken = token)) } } }
    fun loadPresets() { viewModelScope.launch { runCatching { api.cameraPresets(id) }.onSuccess { _presets.value = it } } }
    fun captureSnapshot() {
        if (_snapshotLoading.value) return
        viewModelScope.launch {
            _snapshotLoading.value = true
            val bytes = runCatching { api.downloadCameraSnapshot(id) }.getOrElse {
                _snapshotLoading.value = false
                return@launch
            }
            runCatching { deviceMedia.savePhoto(bytes, mediaName("jpg")) }
                .onSuccess { api.announce(R.string.snapshot_saved) }
                .onFailure { api.announce(R.string.media_save_failed, false) }
            _snapshotLoading.value = false
        }
    }
    fun recordRecentClip() {
        if (_recordingLoading.value) return
        viewModelScope.launch {
            _recordingLoading.value = true
            val bytes = runCatching { api.downloadRecentRecording(id) }.getOrElse {
                _recordingLoading.value = false
                return@launch
            }
            runCatching { deviceMedia.saveVideo(bytes, mediaName("mp4")) }
                .onSuccess { api.announce(R.string.recording_saved) }
                .onFailure { api.announce(R.string.media_save_failed, false) }
            _recordingLoading.value = false
        }
    }
    fun storagePermissionDenied() = api.announce(R.string.storage_permission_required, false)
    private fun mediaName(extension: String): String {
        val cameraName = _camera.value?.name.orEmpty().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "camera" }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "Valkyris-$cameraName-$timestamp.$extension"
    }
    fun liveUrl() = api.liveUrl(id)
    fun token() = api.token()
    fun httpClient() = api.mediaHttpClient()
    override fun onCleared() {
        realtime?.close(1000, "camera detail closed")
        super.onCleared()
    }
}
