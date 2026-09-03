package com.ferforastieri.valkyris.core.model

import kotlinx.serialization.Serializable

@Serializable data class Capabilities(val snapshot:Boolean=false,val events:Boolean=false,val ptz:Boolean=false,val zoom:Boolean=false,val presets:Boolean=false,val audio:Boolean=false)
@Serializable data class Camera(val id:String,val name:String,val host:String,val port:Int=2020,val profileToken:String="",val capabilities:Capabilities=Capabilities(),val enabled:Boolean=true)
@Serializable data class CreateCameraRequest(val name:String,val host:String,val port:Int=2020,val username:String,val password:String,val rtspUri:String)
@Serializable data class ValkyrisEvent(val id:String,val cameraId:String,val ruleId:String?=null,val type:String,val confidence:Double,val occurredAt:String,val snapshotPath:String?=null,val clipPath:String?=null,val acknowledgedAt:String?=null)
@Serializable data class RuleActions(val record:Boolean=true,val notify:Boolean=true,val alarm:Boolean=false)
@Serializable data class RuleSchedule(val days:List<Int> = emptyList(),val start:String="",val end:String="",val timezone:String="")
@Serializable data class Rule(val id:String="",val cameraId:String,val name:String,val detectorTypes:List<String>,val minConfidence:Double=.65,val confirmations:Int=2,val cooldownSeconds:Int=60,val schedule:RuleSchedule=RuleSchedule(),val actions:RuleActions=RuleActions(),val enabled:Boolean=true)
@Serializable data class DetectorKind(val id:String,val label:String,val source:String)
@Serializable data class AuthStatus(val initialized:Boolean)
@Serializable data class LoginRequest(val password:String,val deviceName:String,val locale:String)
@Serializable data class PairRequest(val code:String,val deviceName:String,val locale:String)
@Serializable data class PairResponse(val deviceId:String,val token:String,val admin:Boolean=false)
@Serializable data class PairingSession(val id:String,val code:String,val expiresAt:String)
@Serializable data class PTZCommand(val action:String,val pan:Double=0.0,val tilt:Double=0.0,val zoom:Double=0.0)
@Serializable data class PushRegistration(val endpoint:String,val secret:String)
@Serializable data class ApiEnvelope<T>(val success:Boolean,val message:String="",val data:T?=null,val error:String?=null)
@Serializable data class CameraOperation(val id:String,val status:String,val message:String,val camera:Camera?=null,val createdAt:String="",val updatedAt:String="")
@Serializable data class UpdateInfo(val currentVersion:String="",val clientVersion:String="",val latestVersion:String,val available:Boolean=false,val serverUpdateAvailable:Boolean=false,val apkUpdateAvailable:Boolean=false,val releaseUrl:String="",val apkUrl:String="",val publishedAt:String="",val message:String="")
@Serializable data class UpdateRequest(val clientVersion:String)
