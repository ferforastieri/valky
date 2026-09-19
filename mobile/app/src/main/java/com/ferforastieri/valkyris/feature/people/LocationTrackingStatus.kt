package com.ferforastieri.valkyris.feature.people

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Process-local status contains no coordinates and is shared with the visible screen.
object LocationTrackingStatus {
    enum class State { Idle, Locating, Updated, LocationUnavailable, SendFailed, PermissionRequired }
    private val mutable = MutableStateFlow(State.Idle)
    val state = mutable.asStateFlow()
    internal fun set(state: State) { mutable.value = state }
}
