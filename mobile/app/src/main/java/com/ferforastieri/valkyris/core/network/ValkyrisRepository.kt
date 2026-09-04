package com.ferforastieri.valkyris.core.network

import com.ferforastieri.valkyris.core.database.ValkyrisDao
import com.ferforastieri.valkyris.core.database.EventEntity
import com.ferforastieri.valkyris.core.database.RuleEntity
import com.ferforastieri.valkyris.core.database.PendingActionEntity
import com.ferforastieri.valkyris.core.model.Rule
import com.ferforastieri.valkyris.core.model.Camera
import com.ferforastieri.valkyris.core.model.CreateCameraRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class ValkyrisRepository @Inject constructor(val api:ValkyrisApi,private val dao:ValkyrisDao){
    private val json=Json{ignoreUnknownKeys=true;explicitNulls=false}
    private val _cameras=MutableStateFlow<List<Camera>>(emptyList())
    val cameras=_cameras.asStateFlow()
    fun cachedEvents()=dao.events()
    fun cachedRules()=dao.rules()
    suspend fun refreshCameras():List<Camera>{val value=api.cameras();_cameras.value=value;return value}
    suspend fun createCamera(input:CreateCameraRequest):Camera{val camera=api.createCamera(input);_cameras.update{current->(current+camera).distinctBy(Camera::id)};return camera}
    suspend fun deleteCamera(id:String){api.deleteCamera(id);_cameras.update{current->current.filterNot{it.id==id}}}
    suspend fun refreshEvents(){flushPending();val events=api.events();dao.syncEvents(events.map{EventEntity(it.id,it.cameraId,it.type,it.confidence,it.occurredAt,it.snapshotPath,it.clipPath,it.acknowledgedAt)})}
    suspend fun refreshRules(){flushPending();val rules=api.rules();dao.replaceRules(rules.map{RuleEntity(it.id,it.cameraId,it.name,it.detectorTypes.joinToString(","),it.enabled)})}
    suspend fun acknowledge(eventId:String){runCatching{api.acknowledge(eventId)}.onFailure{dao.savePending(PendingActionEntity("ack:$eventId","ack",eventId,System.currentTimeMillis()))}.getOrThrow()}
    suspend fun createRule(rule:Rule){runCatching{api.createRule(rule)}.onFailure{dao.savePending(PendingActionEntity(UUID.randomUUID().toString(),"rule",json.encodeToString(Rule.serializer(),rule),System.currentTimeMillis()))}.getOrThrow()}
    suspend fun flushPending(){dao.pendingActions().forEach{action->val delivered=runCatching{when(action.kind){"ack"->api.acknowledge(action.payload,announce=false);"rule"->api.createRule(json.decodeFromString(Rule.serializer(),action.payload),announce=false);else->error("unknown pending action")}}.isSuccess;if(delivered)dao.deletePending(action.id)}}
}
