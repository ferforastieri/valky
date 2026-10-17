package com.ferforastieri.valkyris.core.action

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class MobileActionGate @Inject constructor() {
    private val lock = Any()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun tryAcquire(): Boolean = synchronized(lock) {
        if (_busy.value) false else {
            _busy.value = true
            true
        }
    }

    fun release() = synchronized(lock) {
        _busy.value = false
    }
}
