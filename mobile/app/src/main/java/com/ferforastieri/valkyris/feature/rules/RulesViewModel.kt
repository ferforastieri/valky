package com.ferforastieri.valkyris.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.database.RuleEntity
import com.ferforastieri.valkyris.core.model.DetectorKind
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    val rules = repository.cachedRules().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList<RuleEntity>(),
    )
    val cameras = repository.cameras
    private val _detectors = MutableStateFlow<List<DetectorKind>>(emptyList())
    val detectors = _detectors.asStateFlow()
    private val _creating = MutableStateFlow(false)
    val creating = _creating.asStateFlow()

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
        if (!actionGate.tryAcquire()) return
        _creating.value = true
        viewModelScope.launch {
            try {
                val result = runCatching { repository.createRule(rule) }
                if (result.isSuccess) runCatching { repository.refreshRules() }
                onComplete(result.isSuccess)
            } finally {
                _creating.value = false
                actionGate.release()
            }
        }
    }
}
