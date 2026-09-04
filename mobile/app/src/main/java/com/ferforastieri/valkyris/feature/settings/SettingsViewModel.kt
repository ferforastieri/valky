package com.ferforastieri.valkyris.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.model.RetentionSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InvitationState(
    val loading: Boolean = false,
    val uri: String? = null,
    val error: String? = null,
)

data class RetentionState(
    val value: RetentionSettings = RetentionSettings(),
    val loading: Boolean = true,
    val saving: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val api: ValkyrisApi) : ViewModel() {
    private val _invitation = MutableStateFlow(InvitationState())
    val invitation = _invitation.asStateFlow()
    private val _retention = MutableStateFlow(RetentionState())
    val retention = _retention.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { api.retention() }
                .onSuccess { _retention.value = RetentionState(value = it, loading = false) }
                .onFailure { _retention.value = RetentionState(loading = false) }
        }
    }

    fun createInvitation() {
        viewModelScope.launch {
            _invitation.value = InvitationState(loading = true)
            runCatching { api.createPairingSession() }
                .onSuccess { _invitation.value = InvitationState(uri = api.invitationUri(it)) }
                .onFailure { _invitation.value = InvitationState(error = it.message) }
        }
    }

    fun clearInvitation() {
        _invitation.value = InvitationState()
    }

    fun saveRetention(value: RetentionSettings, onSuccess: () -> Unit) {
        if (_retention.value.saving) return
        viewModelScope.launch {
            _retention.value = _retention.value.copy(saving = true)
            runCatching { api.updateRetention(value) }
                .onSuccess {
                    _retention.value = RetentionState(value = it, loading = false)
                    onSuccess()
                }
                .onFailure { _retention.value = _retention.value.copy(saving = false) }
        }
    }
}
