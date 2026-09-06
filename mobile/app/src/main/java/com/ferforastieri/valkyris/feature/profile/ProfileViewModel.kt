package com.ferforastieri.valkyris.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ferforastieri.valkyris.core.action.MobileActionGate
import com.ferforastieri.valkyris.core.model.TrackedPerson
import com.ferforastieri.valkyris.core.network.ValkyrisRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ValkyrisRepository,
    private val actionGate: MobileActionGate,
) : ViewModel() {
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
