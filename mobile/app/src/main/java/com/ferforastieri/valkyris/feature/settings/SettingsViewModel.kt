package com.ferforastieri.valkyris.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.RetentionSettings
import com.ferforastieri.valkyris.core.network.ValkyrisApi
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import com.ferforastieri.valkyris.core.push.FcmRegistration
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

data class PushConfigurationState(
    val configured: Boolean = true,
    val loading: Boolean = true,
    val saving: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ValkyrisApi,
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
    private val push: FcmRegistration,
) : ViewModel() {
    private val _users = MutableStateFlow<List<com.ferforastieri.valkyris.core.model.ManagedUser>>(emptyList())
    val users = _users.asStateFlow()
    private val _usersError = MutableStateFlow<String?>(null)
    val usersError = _usersError.asStateFlow()
    private val _usersBusy = MutableStateFlow(false)
    val usersBusy = _usersBusy.asStateFlow()
    fun refreshUsers() { viewModelScope.launch { runCatching { api.managedUsers() }.onSuccess { _users.value = it; _usersError.value = null }.onFailure { _usersError.value = it.message } } }
    fun saveUser(user: com.ferforastieri.valkyris.core.model.ManagedUser, remove: Boolean = false) {
        if (_usersBusy.value) return
        _usersBusy.value = true
        viewModelScope.launch { try {
            runCatching { if (remove) api.removeUser(user.id) else api.manageUser(user) }
                .onSuccess { refreshUsers() }.onFailure { _usersError.value = it.message }
        } finally { _usersBusy.value = false } }
    }
    private val _invitation = MutableStateFlow(InvitationState())
    val invitation = _invitation.asStateFlow()
    private val _retention = MutableStateFlow(RetentionState())
    val retention = _retention.asStateFlow()
    private val _pushConfiguration = MutableStateFlow(PushConfigurationState())
    val pushConfiguration = _pushConfiguration.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { api.retention() }
                .onSuccess { _retention.value = RetentionState(value = it, loading = false) }
                .onFailure { _retention.value = RetentionState(loading = false) }
        }
        refreshPushConfiguration()
    }

    fun refreshPushConfiguration() {
        viewModelScope.launch {
            runCatching { api.pushConfiguration() }
                .onSuccess { _pushConfiguration.value = PushConfigurationState(configured = it.configured, loading = false) }
                // Do not block the application while the server is temporarily unreachable.
                .onFailure { _pushConfiguration.value = PushConfigurationState(configured = true, loading = false) }
        }
    }

    fun saveFirebaseServiceAccount(data: ByteArray) {
        if (data.isEmpty() || data.size > 256 * 1024 || !actionGate.tryAcquire()) return
        _pushConfiguration.value = _pushConfiguration.value.copy(saving = true)
        viewModelScope.launch {
            try {
                runCatching { api.saveFirebaseServiceAccount(data) }
                    .onSuccess {
                        _pushConfiguration.value = PushConfigurationState(configured = it.configured, loading = false)
                        push.registerCurrent()
                    }
                    .onFailure { _pushConfiguration.value = _pushConfiguration.value.copy(saving = false) }
            } finally {
                actionGate.release()
            }
        }
    }

    fun createInvitation() {
        if (!actionGate.tryAcquire()) return
        _invitation.value = InvitationState(loading = true)
        viewModelScope.launch {
            try {
                runCatching { api.createPairingSession() }
                    .onSuccess { _invitation.value = InvitationState(uri = api.invitationUri(it)) }
                    .onFailure { _invitation.value = InvitationState(error = it.message) }
            } finally {
                actionGate.release()
            }
        }
    }

    fun clearInvitation() {
        _invitation.value = InvitationState()
    }

    fun saveRetention(value: RetentionSettings, onSuccess: () -> Unit) {
        if (!actionGate.tryAcquire()) return
        _retention.value = _retention.value.copy(saving = true)
        viewModelScope.launch {
            try {
                runCatching { api.updateRetention(value) }
                    .onSuccess {
                        _retention.value = RetentionState(value = it, loading = false)
                        onSuccess()
                    }
                    .onFailure { _retention.value = _retention.value.copy(saving = false) }
            } finally {
                actionGate.release()
            }
        }
    }
}
