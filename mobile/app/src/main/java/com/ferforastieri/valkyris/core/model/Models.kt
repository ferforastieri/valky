package com.ferforastieri.valkyris.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class Capabilities(val snapshot:Boolean=false,val events:Boolean=false,val ptz:Boolean=false,val zoom:Boolean=false,val audio:Boolean=false)
@Serializable data class Camera(val id:String,val name:String,val host:String,val icon:String="camera",val port:Int=2020,val profileToken:String="",val capabilities:Capabilities=Capabilities(),val setupStatus:String="ready",val setupStep:String="",val setupError:String="",val setupUpdatedAt:String="",val startupPresetToken:String="",val favorite:Boolean=false,val enabled:Boolean=true,val alerts:AlertPresentation=AlertPresentation())
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class CreateCameraRequest(val name:String,val icon:String="camera",val host:String,val port:Int=2020,val username:String,val password:String,@kotlinx.serialization.EncodeDefault val alerts:AlertPresentation=AlertPresentation())
@Serializable data class LightCapabilities(val brightness:Boolean=true,val color:Boolean=true,val colorTemperature:Boolean=true)
@Serializable data class LightColor(val hue:Int=0,val saturation:Int=100,val value:Int=100)
@Serializable data class LightState(val online:Boolean=false,val power:Boolean=false,val mode:String="white",val brightness:Int=100,val temperatureKelvin:Int=4000,val color:LightColor=LightColor(),val updatedAt:String="")
@Serializable data class LightDevice(val id:String,val name:String,val room:String="",val capabilities:LightCapabilities=LightCapabilities(),val setupStatus:String="pending",val setupError:String="",val favorite:Boolean=false,val enabled:Boolean=true,val lastSeenAt:String?=null,val state:LightState=LightState())
@Serializable data class LightStatePatch(val power:Boolean?=null,val mode:String?=null,val brightness:Int?=null,val temperatureKelvin:Int?=null,val color:LightColor?=null)
@Serializable data class RemoteDevice(val id:String="",val name:String,val kind:String="smart_tv",val host:String,val port:Int=8002,val mac:String="",val setupStatus:String="pending",val setupError:String="",val tokenConfigured:Boolean=false,val favorite:Boolean=false,val enabled:Boolean=true,val lastSeenAt:String?=null,val createdAt:String="",val updatedAt:String="")
@Serializable data class RemoteInput(val name:String,val kind:String="smart_tv",val host:String,val port:Int=8002)
@Serializable data class RemoteUpdate(val name:String?=null,val host:String?=null,val port:Int?=null,val mac:String?=null,val enabled:Boolean?=null)
@Serializable data class RemoteKeyInput(val key:String)
@Serializable data class RemoteTextInput(val text:String)
@Serializable data class FavoriteInput(val favorite:Boolean)
@Serializable data class TypeCount(val type:String,val count:Int)
@Serializable data class DayCount(val date:String,val count:Int)
@Serializable data class CameraStats(val days:Int=30,val total:Int=0,val unread:Int=0,val byType:List<TypeCount> = emptyList(),val byDay:List<DayCount> = emptyList(),val byHour:List<Int> = emptyList(),val averageConfidence:Double=0.0,val withClip:Int=0,val withSnapshot:Int=0,val firstEventAt:String="",val lastEventAt:String="")
@Serializable data class ValkyrisEvent(val id:String,val cameraId:String="",val ruleId:String?=null,val source:String="camera",val subjectId:String="",val type:String,val confidence:Double,val occurredAt:String,val snapshotPath:String?=null,val clipPath:String?=null,val clipStatus:String="not_requested",val clipError:String?=null,val metadata:Map<String,JsonElement> = emptyMap(),val acknowledgedAt:String?=null)
@Serializable data class RuleActions(val record:Boolean=true,val notify:Boolean=true,val alarm:Boolean=false,val alerts:AlertPresentation=AlertPresentation())
@Serializable data class RuleSchedule(val days:List<Int> = emptyList(),val start:String="",val end:String="",val timezone:String="")
@Serializable data class MotionRegion(val x:Double,val y:Double,val width:Double,val height:Double)
@Serializable data class MotionSettings(val region:MotionRegion,val minDurationSeconds:Int=10,val minChangedFraction:Double=0.05)
@Serializable data class Rule(val id:String="",val cameraId:String,val name:String,val detectorTypes:List<String>,val confirmations:Int=1,val cooldownSeconds:Int=60,val schedule:RuleSchedule=RuleSchedule(),val motion:MotionSettings?=null,val actions:RuleActions=RuleActions(),val enabled:Boolean=true)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class RuleActionsRequest(val record:Boolean,val notify:Boolean,val alarm:Boolean,@kotlinx.serialization.EncodeDefault val alerts:AlertPresentation=AlertPresentation())
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class RuleUpsertRequest(val cameraId:String,val name:String,val detectorTypes:List<String>,val actions:RuleActionsRequest,val schedule:RuleSchedule=RuleSchedule(),val motion:MotionSettings?=null,@kotlinx.serialization.EncodeDefault val cooldownSeconds:Int=60,@kotlinx.serialization.EncodeDefault val confirmations:Int=1)
@Serializable data class DetectorKind(val id:String,val label:String,val source:String)
@Serializable data class AuthStatus(val initialized:Boolean)
@Serializable data class LoginRequest(val password:String,val deviceName:String,val userName:String="",val locale:String,val username:String="")
@Serializable data class ChangePasswordRequest(val currentPassword:String,val newPassword:String)
@Serializable data class PairRequest(val code:String,val deviceName:String,val userName:String="",val locale:String,val username:String="",val password:String="")
@Serializable data class PairResponse(val deviceId:String,val token:String,val admin:Boolean=false)
@Serializable data class PairingSession(val id:String,val code:String,val expiresAt:String)
@Serializable data class PTZCommand(val action:String,val pan:Double=0.0,val tilt:Double=0.0,val zoom:Double=0.0)
@Serializable data class PTZPreset(val token:String,val name:String)
@Serializable data class PresetInput(val name:String)
@Serializable data class StartupPresetInput(val token:String)
@Serializable data class PushRegistration(val token:String,val secret:String)
@Serializable data class PushConfiguration(val configured:Boolean=false)
@Serializable data class FirebaseServiceAccountUpload(val serviceAccountBase64:String)
@Serializable data class ApiEnvelope<T>(val success:Boolean,val message:String="",val data:T?=null,val error:String?=null)
@Serializable data class CameraOperation(val id:String,val status:String,val message:String,val camera:Camera?=null,val createdAt:String="",val updatedAt:String="")
@Serializable data class UpdateInfo(val currentVersion:String="",val clientVersion:String="",val latestVersion:String,val available:Boolean=false,val serverUpdateAvailable:Boolean=false,val apkUpdateAvailable:Boolean=false,val releaseUrl:String="",val apkUrl:String="",val publishedAt:String="",val message:String="")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class RetentionSettings(@kotlinx.serialization.EncodeDefault val maxAgeDays:Int=7,@kotlinx.serialization.EncodeDefault val maxStorageGB:Long=5)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class TrackedPerson(val id:String="",val name:String,val color:String="#5B5BD6",val avatarData:String="",val deviceId:String="",val enabled:Boolean=true,val lastLatitude:Double?=null,val lastLongitude:Double?=null,val lastAccuracy:Double?=null,val lastLocatedAt:String?=null,val battery:Int=-1,val batteryAt:String?=null,@kotlinx.serialization.EncodeDefault val notifyCamera:Boolean=true,@kotlinx.serialization.EncodeDefault val notifyLocation:Boolean=true,val createdAt:String="",val updatedAt:String="")
@Serializable data class TrackedPlace(val id:String="",val name:String,val latitude:Double,val longitude:Double,val radiusMeters:Double=100.0,val enabled:Boolean=true,val createdAt:String="",val updatedAt:String="")
@Serializable data class PlaceUpsertRequest(val name:String,val latitude:Double,val longitude:Double,val radiusMeters:Double,val enabled:Boolean)
@Serializable data class PersonLocation(val id:String="",val personId:String="",val latitude:Double,val longitude:Double,val accuracy:Double=0.0,val address:String="",val occurredAt:String="",val lastSeenAt:String="")

@Serializable data class LocationReportResult(val transitions: Int = 0, val pendingConfirmations: Int = 0)

@Serializable data class ActivityBucket(val start: String, val end: String, val count: Int)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class LocationReport(val latitude: Double, val longitude: Double, val accuracy: Double, val occurredAt: String, @kotlinx.serialization.EncodeDefault val battery: Int = -1)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class ManagedUser(val id:String="",val name:String,@kotlinx.serialization.EncodeDefault val enabled:Boolean=true,val admin:Boolean=false,val devices:Int=0,val username:String="",val credentialsConfigured:Boolean=false,val viewRules:Boolean=false,val editRules:Boolean=false,@kotlinx.serialization.EncodeDefault val notifyCamera:Boolean=true,@kotlinx.serialization.EncodeDefault val notifyLocation:Boolean=true,val avatarData:String="",val password:String="")

@Serializable data class SessionPermissions(val admin:Boolean=false,val readOnly:Boolean=false,val viewRules:Boolean=false,val editRules:Boolean=false,val username:String="",val credentialsConfigured:Boolean=false)

@Serializable data class AlertPresentation(
    val notificationTitle: String = "",
    val notificationBody: String = "",
    val alarmTitle: String = "",
    val alarmBody: String = "",
    val alarmSound: String = "alarm",
    val vibrate: Boolean = true,
    val fullScreen: Boolean = true,
)
