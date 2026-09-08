package com.ferforastieri.valkyris.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class Capabilities(val snapshot:Boolean=false,val events:Boolean=false,val ptz:Boolean=false,val zoom:Boolean=false,val audio:Boolean=false)
@Serializable data class Camera(val id:String,val name:String,val host:String,val icon:String="camera",val port:Int=2020,val profileToken:String="",val capabilities:Capabilities=Capabilities(),val setupStatus:String="ready",val setupStep:String="",val setupError:String="",val setupUpdatedAt:String="",val enabled:Boolean=true)
@Serializable data class CreateCameraRequest(val name:String,val icon:String="camera",val host:String,val port:Int=2020,val username:String,val password:String)
@Serializable data class ValkyrisEvent(val id:String,val cameraId:String="",val ruleId:String?=null,val source:String="camera",val subjectId:String="",val type:String,val confidence:Double,val occurredAt:String,val snapshotPath:String?=null,val clipPath:String?=null,val clipStatus:String="not_requested",val clipError:String?=null,val metadata:Map<String,JsonElement> = emptyMap(),val acknowledgedAt:String?=null)
@Serializable data class RuleActions(val record:Boolean=true,val notify:Boolean=true,val alarm:Boolean=false,val recipientUserIds:List<String>?=null)
@Serializable data class RuleSchedule(val days:List<Int> = emptyList(),val start:String="",val end:String="",val timezone:String="")
@Serializable data class MotionRegion(val x:Double,val y:Double,val width:Double,val height:Double)
@Serializable data class MotionSettings(val region:MotionRegion,val minDurationSeconds:Int=10,val minChangedFraction:Double=0.05)
@Serializable data class Rule(val id:String="",val cameraId:String,val name:String,val detectorTypes:List<String>,val confirmations:Int=1,val cooldownSeconds:Int=60,val schedule:RuleSchedule=RuleSchedule(),val motion:MotionSettings?=null,val actions:RuleActions=RuleActions(),val enabled:Boolean=true)
@Serializable data class RuleActionsRequest(val record:Boolean,val notify:Boolean,val alarm:Boolean,val recipientUserIds:List<String>?=null)
@Serializable data class RuleUpsertRequest(val cameraId:String,val name:String,val detectorTypes:List<String>,val actions:RuleActionsRequest,val schedule:RuleSchedule=RuleSchedule(),val motion:MotionSettings?=null,val cooldownSeconds:Int=60,val confirmations:Int=1)
@Serializable data class DetectorKind(val id:String,val label:String,val source:String)
@Serializable data class AuthStatus(val initialized:Boolean)
@Serializable data class LoginRequest(val password:String,val deviceName:String,val userName:String="",val locale:String)
@Serializable data class ChangePasswordRequest(val currentPassword:String,val newPassword:String)
@Serializable data class PairRequest(val code:String,val deviceName:String,val userName:String="",val locale:String)
@Serializable data class PairResponse(val deviceId:String,val token:String,val admin:Boolean=false)
@Serializable data class PairingSession(val id:String,val code:String,val expiresAt:String)
@Serializable data class PTZCommand(val action:String,val pan:Double=0.0,val tilt:Double=0.0,val zoom:Double=0.0)
@Serializable data class PushRegistration(val token:String,val secret:String)
@Serializable data class PushConfiguration(val configured:Boolean=false)
@Serializable data class FirebaseServiceAccountUpload(val serviceAccountBase64:String)
@Serializable data class ApiEnvelope<T>(val success:Boolean,val message:String="",val data:T?=null,val error:String?=null)
@Serializable data class CameraOperation(val id:String,val status:String,val message:String,val camera:Camera?=null,val createdAt:String="",val updatedAt:String="")
@Serializable data class UpdateInfo(val currentVersion:String="",val clientVersion:String="",val latestVersion:String,val available:Boolean=false,val serverUpdateAvailable:Boolean=false,val apkUpdateAvailable:Boolean=false,val releaseUrl:String="",val apkUrl:String="",val publishedAt:String="",val message:String="")
@Serializable data class UpdateRequest(val clientVersion:String)
@Serializable data class RetentionSettings(val maxAgeDays:Int=7,val maxStorageGB:Long=5)
@Serializable data class TrackedPerson(val id:String="",val name:String,val color:String="#5B5BD6",val avatarData:String="",val deviceId:String="",val enabled:Boolean=true,val lastLatitude:Double?=null,val lastLongitude:Double?=null,val lastAccuracy:Double?=null,val lastLocatedAt:String?=null,val createdAt:String="",val updatedAt:String="")
@Serializable data class TrackedPlace(val id:String="",val name:String,val latitude:Double,val longitude:Double,val radiusMeters:Double=100.0,val enabled:Boolean=true,val createdAt:String="",val updatedAt:String="")
@Serializable data class PlaceUpsertRequest(val name:String,val latitude:Double,val longitude:Double,val radiusMeters:Double,val enabled:Boolean)
@Serializable data class PersonLocation(val id:String="",val personId:String="",val latitude:Double,val longitude:Double,val accuracy:Double=0.0,val address:String="",val occurredAt:String="",val lastSeenAt:String="")

@Serializable data class LocationReportResult(val transitions: Int = 0, val pendingConfirmations: Int = 0)

@Serializable data class ActivityBucket(val start: String, val end: String, val count: Int)

@Serializable data class LocationReport(val latitude: Double, val longitude: Double, val accuracy: Double, val occurredAt: String)

@Serializable data class ManagedUser(val id:String="",val name:String,val enabled:Boolean=true,val admin:Boolean=false,val devices:Int=0)
