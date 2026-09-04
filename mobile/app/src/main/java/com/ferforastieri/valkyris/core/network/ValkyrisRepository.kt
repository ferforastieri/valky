package com.ferforastieri.valkyris.core.network

import com.ferforastieri.valkyris.core.database.EventEntity
import com.ferforastieri.valkyris.core.database.RuleEntity
import com.ferforastieri.valkyris.core.database.ValkyrisDao
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import com.ferforastieri.valkyris.core.model.Rule
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class ValkyrisRepository @Inject constructor(
    val api: ValkyrisApi,
    private val dao: ValkyrisDao,
) {
    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras = _cameras.asStateFlow()

    fun cachedEvents() = dao.events()
    fun cachedRules() = dao.rules()

    suspend fun refreshCameras(): List<Camera> {
        val value = api.cameras()
        _cameras.value = value
        return value
    }

    suspend fun createCamera(input: CreateCameraRequest): Camera {
        val camera = api.createCamera(input)
        _cameras.update { current -> (current + camera).distinctBy(Camera::id) }
        return camera
    }

    suspend fun deleteCamera(id: String) {
        api.deleteCamera(id)
        _cameras.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun refreshEvents() {
        val events = api.events()
        dao.syncEvents(events.map { EventEntity(it.id, it.cameraId, it.type, it.confidence, it.occurredAt, it.snapshotPath, it.clipPath, it.acknowledgedAt) })
    }

    suspend fun refreshRules() {
        val rules = api.rules()
        dao.replaceRules(rules.map(Rule::toEntity))
    }

    suspend fun acknowledge(eventId: String) {
        api.acknowledge(eventId)
    }

    suspend fun createRule(rule: Rule): Rule {
        val created = api.createRule(rule)
        dao.saveRule(created.toEntity())
        return created
    }
}

private fun Rule.toEntity() = RuleEntity(id, cameraId, name, detectorTypes.joinToString(","), enabled)
