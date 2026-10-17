package com.ferforastieri.valky.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valky.core.model.LightStatePatch
import com.ferforastieri.valky.core.network.ValkyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LightDetailViewModel @Inject constructor(
    private val repository: ValkyRepository,
) : ViewModel() {
    val lights = repository.lights

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _missing = MutableStateFlow(false)
    val missing = _missing.asStateFlow()

    /** Garante a lâmpada pedida, mesmo que o cache ainda esteja vazio. */
    fun ensure(id: String) {
        if (repository.lights.value.any { it.id == id }) return
        viewModelScope.launch {
            runCatching { repository.refreshLights() }
            _missing.value = repository.lights.value.none { it.id == id }
        }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repository.refreshLights() } }
    }

    fun control(id: String, patch: LightStatePatch) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                runCatching { repository.controlLight(id, patch) }
            } finally {
                _busy.value = false
            }
        }
    }
}
