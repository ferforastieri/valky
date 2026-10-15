package com.ferforastieri.valkyris.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.model.LightStatePatch
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LightDetailViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
) : ViewModel() {
    val lights = repository.lights

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

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
