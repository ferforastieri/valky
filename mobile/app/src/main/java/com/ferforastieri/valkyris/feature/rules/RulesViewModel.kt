package com.ferforastieri.valkyris.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.DetectorKind
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    val rules = repository.rules
    val cameras = repository.cameras
    private val _detectors = MutableStateFlow<List<DetectorKind>>(emptyList())
    val detectors = _detectors.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            runCatching { repository.refreshCameras() }
            _detectors.value = runCatching { repository.api.detectors() }.getOrDefault(emptyList())
        }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repository.refreshRules() } }
    }

    fun create(rule: Rule, onComplete: (Boolean) -> Unit) {
        submit(onComplete) { repository.createRule(rule) }
    }

    fun update(id: String, rule: Rule, onComplete: (Boolean) -> Unit) {
        submit(onComplete) { repository.updateRule(id, rule) }
    }

    fun delete(id: String, onComplete: (Boolean) -> Unit = {}) {
        if (!actionGate.tryAcquire()) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val result = runCatching { repository.deleteRule(id) }
                if (result.isSuccess) runCatching { repository.refreshRules() }
                onComplete(result.isSuccess)
            } finally {
                _saving.value = false
                actionGate.release()
            }
        }
    }

    private fun submit(onComplete: (Boolean) -> Unit, action: suspend () -> Rule) {
        if (!actionGate.tryAcquire()) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val result = runCatching { action() }
                if (result.isSuccess) runCatching { repository.refreshRules() }
                onComplete(result.isSuccess)
            } finally {
                _saving.value = false
                actionGate.release()
            }
        }
    }
}
