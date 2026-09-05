package com.ferforastieri.valkyris.core.network

import android.net.Uri
import androidx.annotation.StringRes
import com.ferforastieri.valkyris.BuildConfig
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.core.model.*
import com.ferforastieri.valkyris.core.security.Session
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class ApiNotice(@param:StringRes val messageRes: Int, val success: Boolean)

class ValkyrisApi(
    private val session: () -> Session?,
    private val resolveString: (Int) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val _notices = MutableSharedFlow<ApiNotice>(extraBufferCapacity = 32)
    val notices = _notices.asSharedFlow()
    private val apiClients = ConcurrentHashMap<String, HttpClient>()
    private val mediaClients = ConcurrentHashMap<String, okhttp3.OkHttpClient>()

    fun mediaHttpClient(): okhttp3.OkHttpClient {
        val fingerprint = requireNotNull(session()).fingerprint
        return mediaClients.computeIfAbsent(fingerprint) { pinnedClient(it) }
    }

    private fun pinnedClient(fingerprint: String) = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .apply {
            if (fingerprint.isNotBlank()) {
                val trust = PinnedTrustManager(fingerprint)
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                sslSocketFactory(ssl.socketFactory, trust)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    private fun client(fingerprint: String) = apiClients.computeIfAbsent(fingerprint) {
        HttpClient(OkHttp) {
            expectSuccess = false
            engine { preconfigured = pinnedClient(fingerprint) }
            install(ContentNegotiation) { json(json) }
        }
    }

    private fun base() = requireNotNull(session()) { "Valkyris is not paired" }.baseUrl + "/api/v1"

    private suspend inline fun <reified T> execute(
        fingerprint: String,
        @StringRes successNotice: Int? = null,
        announceError: Boolean = false,
        crossinline request: suspend (HttpClient) -> HttpResponse,
    ): T {
        try {
            return client(fingerprint).let { http ->
                val response = request(http)
                val raw = response.bodyAsText()
                val envelope = runCatching { json.decodeFromString<ApiEnvelope<T>>(raw) }.getOrElse { decodeError ->
                    val fallback = response.headers[HEADER_MESSAGE].orEmpty().ifBlank {
                        raw.ifBlank { "${response.status.value} ${response.status.description}" }
                    }
                    if (!response.status.isSuccess()) throw localizedError(fallback, response.status.value, decodeError)
                    throw localizedError("Invalid Valkyris response: $fallback", response.status.value, decodeError)
                }
                val message = envelope.message.ifBlank {
                    response.headers[HEADER_MESSAGE].orEmpty().ifBlank { response.status.description }
                }
                if (!response.status.isSuccess() || !envelope.success) {
                    val complete = envelope.error?.takeIf { it.isNotBlank() } ?: message
                    throw localizedError(complete, response.status.value)
                }
                successNotice?.let { publish(it, true) }
                envelope.data ?: throw localizedError("Valkyris response did not include data: $message", response.status.value)
            }
        } catch (error: Throwable) {
            if (error is ApiException) {
                if (announceError) publish(error.messageRes, false)
                throw error
            }
            val complete = buildString {
                append(error::class.simpleName ?: "Network error")
                error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
            val localized = localizedError(complete, cause = error)
            if (announceError) publish(localized.messageRes, false)
            throw localized
        }
    }

    private suspend fun executeUnit(
        fingerprint: String,
        @StringRes successNotice: Int? = null,
        announceError: Boolean = false,
        request: suspend (HttpClient) -> HttpResponse,
    ) {
        execute<JsonElement>(fingerprint, successNotice, announceError, request)
    }

    private suspend inline fun <reified T> get(path: String): T {
        val current = requireNotNull(session())
        return execute(current.fingerprint) {
            it.get(base() + path) { bearerAuth(current.token) }
        }
    }

    private suspend inline fun <reified T, reified B> post(
        path: String,
        body: B,
        @StringRes successNotice: Int? = null,
        announceError: Boolean = false,
    ): T {
        val current = requireNotNull(session())
        return execute(current.fingerprint, successNotice, announceError) {
            it.post(base() + path) {
                bearerAuth(current.token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    private suspend inline fun <reified T, reified B> put(
        path: String,
        body: B,
        @StringRes successNotice: Int? = null,
        announceError: Boolean = false,
    ): T {
        val current = requireNotNull(session())
        return execute(current.fingerprint, successNotice, announceError) {
            it.put(base() + path) {
                bearerAuth(current.token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    suspend fun authStatus(baseUrl: String): AuthStatus = execute("") {
        it.get(baseUrl.trimEnd('/') + "/api/v1/auth/status")
    }

    suspend fun login(baseUrl: String, request: LoginRequest, bootstrap: Boolean = false): PairResponse = execute("") {
        it.post(baseUrl.trimEnd('/') + if (bootstrap) "/api/v1/admin/bootstrap" else "/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun pair(baseUrl: String, fingerprint: String, request: PairRequest): PairResponse = execute(fingerprint) {
        it.post(baseUrl.trimEnd('/') + "/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun createPairingSession(): PairingSession {
        val current = requireNotNull(session())
        return execute(current.fingerprint) {
            it.post(base() + "/pairing-sessions") { bearerAuth(current.token) }
        }
    }

    fun invitationUri(pairing: PairingSession): String {
        val current = requireNotNull(session())
        return Uri.Builder().scheme("valkyris").authority("pair")
            .appendQueryParameter("url", current.baseUrl)
            .appendQueryParameter("code", pairing.code)
            .apply { if (current.fingerprint.isNotBlank()) appendQueryParameter("fingerprint", current.fingerprint) }
            .build().toString()
    }

    suspend fun cameras(): List<Camera> = get("/cameras")

    suspend fun createCamera(camera: CreateCameraRequest): Camera {
        val started: CameraOperation = post("/cameras", camera, R.string.notice_camera_created, announceError = true)
        return requireNotNull(started.camera) { "The server did not return the saved camera" }
    }

    suspend fun deleteCamera(id: String) {
        val current = requireNotNull(session())
        executeUnit(current.fingerprint, R.string.notice_camera_deleted, announceError = true) {
            it.delete(base() + "/cameras/$id") { bearerAuth(current.token) }
        }
    }

    suspend fun updateInfo(): UpdateInfo = get("/system/update?clientVersion=${BuildConfig.VERSION_NAME.encodeURLParameter()}")

    suspend fun startUpdate(): UpdateInfo = post("/system/update", UpdateRequest(BuildConfig.VERSION_NAME), R.string.notice_update_started, announceError = true)

    suspend fun retention(): RetentionSettings = get("/settings/retention")

    suspend fun updateRetention(settings: RetentionSettings): RetentionSettings = put("/settings/retention", settings, R.string.notice_retention_saved, announceError = true)

    suspend fun events(): List<ValkyrisEvent> = get("/events?limit=100")
    suspend fun event(id: String): ValkyrisEvent = get("/events/$id")
    suspend fun rules(): List<Rule> = get("/rules")
    suspend fun detectors(): List<DetectorKind> = get("/detectors")
    suspend fun createRule(rule: Rule, announce: Boolean = true): Rule = post(
        "/rules",
        rule,
        successNotice = R.string.notice_rule_created.takeIf { announce },
        announceError = announce,
    )

    suspend fun acknowledge(id: String, announce: Boolean = true) {
        val current = requireNotNull(session())
        executeUnit(
            current.fingerprint,
            successNotice = R.string.notice_event_acknowledged.takeIf { announce },
            announceError = announce,
        ) {
            it.post(base() + "/events/$id/acknowledge") { bearerAuth(current.token) }
        }
    }

    suspend fun acknowledgeAll(announce: Boolean = true) {
        val current = requireNotNull(session())
        executeUnit(
            current.fingerprint,
            successNotice = R.string.notice_events_acknowledged.takeIf { announce },
            announceError = announce,
        ) {
            it.post(base() + "/events/acknowledge-all") { bearerAuth(current.token) }
        }
    }

    suspend fun ptz(cameraId: String, command: PTZCommand) {
        val current = requireNotNull(session())
        executeUnit(current.fingerprint, announceError = true) {
            it.post(base() + "/cameras/$cameraId/ptz") {
                bearerAuth(current.token)
                contentType(ContentType.Application.Json)
                setBody(command)
            }
        }
    }

    suspend fun registerPush(registration: PushRegistration) {
        val current = requireNotNull(session())
        executeUnit(current.fingerprint) {
            it.post(base() + "/devices/push") {
                bearerAuth(current.token)
                contentType(ContentType.Application.Json)
                setBody(registration)
            }
        }
    }

    fun snapshotUrl(cameraId: String) = base() + "/cameras/$cameraId/snapshot"
    fun recordingUrl(cameraId: String) = base() + "/cameras/$cameraId/recording"
    fun liveUrl(cameraId: String) = base() + "/cameras/$cameraId/live/index.m3u8"
    fun clipUrl(eventId: String) = base() + "/events/$eventId/clip"
    fun eventSnapshotUrl(eventId: String) = base() + "/events/$eventId/snapshot"
    fun token() = requireNotNull(session()).token

    suspend fun downloadCameraSnapshot(cameraId: String): ByteArray = downloadMedia(snapshotUrl(cameraId))

    suspend fun downloadRecentRecording(cameraId: String): ByteArray = downloadMedia(recordingUrl(cameraId))

    fun announce(@StringRes messageRes: Int, success: Boolean = true) = publish(messageRes, success)

    private suspend fun downloadMedia(url: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()
            mediaHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body.string()
                    throw localizedError(raw, response.code)
                }
                response.body.bytes()
            }
        } catch (error: Throwable) {
            val localized = if (error is ApiException) error else localizedError(error.message.orEmpty(), cause = error)
            publish(localized.messageRes, false)
            throw localized
        }
    }

    fun realtime(onMessage: () -> Unit, onDisconnect: () -> Unit = {}): WebSocket {
        val current = requireNotNull(session())
        val wsUrl = current.baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            .trimEnd('/') + "/api/v1/realtime"
        val request = Request.Builder().url(wsUrl).header("Authorization", "Bearer ${current.token}").build()
        return pinnedClient(current.fingerprint).newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onMessage()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDisconnect()
        })
    }

    private fun publish(@StringRes messageRes: Int, success: Boolean) {
        _notices.tryEmit(ApiNotice(messageRes, success))
    }

    private fun localizedError(raw: String, status: Int? = null, cause: Throwable? = null): ApiException {
        val messageRes = if (status == null) networkErrorNotice(raw) else errorNotice(status, raw)
        return ApiException(resolveString(messageRes), status, cause, raw, messageRes)
    }

    @StringRes
    private fun errorNotice(status: Int?, message: String): Int = when {
        status == 400 -> R.string.error_invalid_request
        status == 401 -> R.string.error_authentication
        status == 403 -> R.string.error_permission
        status == 404 -> R.string.error_not_found
        status == 409 -> R.string.error_conflict
        status != null && status >= 500 -> R.string.error_server
        else -> networkErrorNotice(message)
    }

    @StringRes
    private fun networkErrorNotice(message: String): Int {
        val normalized = message.lowercase()
        return when {
            "timeout" in normalized || "timed out" in normalized -> R.string.error_timeout
            "eof" in normalized || "connection reset" in normalized -> R.string.error_connection_interrupted
            "unable to resolve" in normalized || "failed to connect" in normalized || "connectexception" in normalized -> R.string.error_connection
            else -> R.string.error_action_failed
        }
    }

    companion object {
        private const val HEADER_MESSAGE = "X-Valkyris-Message"
    }
}

class ApiException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
    val technicalMessage: String = message,
    @param:StringRes val messageRes: Int = R.string.error_action_failed,
) : Exception(message, cause)

private class PinnedTrustManager(fingerprint: String) : X509TrustManager {
    private val expected = fingerprint.replace(":", "").uppercase()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Server sent no certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
        if (actual != expected) throw java.security.cert.CertificateException("Valkyris certificate fingerprint does not match")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
