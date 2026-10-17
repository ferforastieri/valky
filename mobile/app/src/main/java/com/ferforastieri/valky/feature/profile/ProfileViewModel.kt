package com.ferforastieri.valky.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valky.core.action.MobileActionGate
import com.ferforastieri.valky.core.model.TrackedPerson
import com.ferforastieri.valky.core.network.ValkyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ValkyRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
    val permissions = repository.api.permissions
    val profile = repository.me
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch { runCatching { repository.refreshMe() } }

    fun save(profile: TrackedPerson) = action { repository.updateMe(profile) }

    fun changePassword(currentPassword: String, newPassword: String) = action {
        repository.changeHomePassword(currentPassword, newPassword)
    }

    private fun action(block: suspend () -> Unit) {
        if (!actionGate.tryAcquire()) return
        _saving.value = true
        viewModelScope.launch {
            try { runCatching { block() } }
            finally {
                _saving.value = false
                actionGate.release()
            }
        }
    }
}
