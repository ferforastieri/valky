package com.ferforastieri.valkyris.core.network

import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.ValkyrisEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// This repository intentionally keeps no persistent/offline cache. Every refresh
// reads the current data from the Valkyris API.
@Singleton
class ValkyrisRepository @Inject constructor(
    val api: ValkyrisApi,
) {
    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras = _cameras.asStateFlow()
    private val _events = MutableStateFlow<List<ValkyrisEvent>>(emptyList())
    val events = _events.asStateFlow()
    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules = _rules.asStateFlow()

    suspend fun refreshCameras(): List<Camera> {
        return api.cameras().also { _cameras.value = it }
    }

    suspend fun createCamera(input: CreateCameraRequest): Camera {
        return api.createCamera(input).also { camera ->
            _cameras.update { current -> (current + camera).distinctBy(Camera::id) }
        }
    }

    suspend fun updateCamera(id: String, input: CreateCameraRequest): Camera {
        return api.updateCamera(id, input).also { camera ->
            _cameras.update { current -> current.map { if (it.id == id) camera else it } }
        }
    }

    suspend fun deleteCamera(id: String) {
        api.deleteCamera(id)
        _cameras.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun refreshEvents(): List<ValkyrisEvent> {
        return api.events().also { _events.value = it }
    }

    suspend fun refreshRules(): List<Rule> {
        return api.rules().also { _rules.value = it }
    }

    suspend fun acknowledge(eventId: String) {
        api.acknowledge(eventId)
    }

    suspend fun acknowledgeAll() {
        api.acknowledgeAll()
    }

    suspend fun createRule(rule: Rule): Rule {
        return api.createRule(rule).also { created ->
            _rules.update { current -> (current + created).distinctBy(Rule::id) }
        }
    }

    suspend fun updateRule(id: String, rule: Rule): Rule {
        return api.updateRule(id, rule).also { updated ->
            _rules.update { current -> current.map { if (it.id == id) updated else it } }
        }
    }

    suspend fun deleteRule(id: String) {
        api.deleteRule(id)
        _rules.update { current -> current.filterNot { it.id == id } }
    }
}
