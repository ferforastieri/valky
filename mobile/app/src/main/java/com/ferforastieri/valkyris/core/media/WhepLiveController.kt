package com.ferforastieri.valkyris.core.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal WHEP reader. HTTP is used only to exchange SDP; rendered media is a
 * direct WebRTC peer connection and never flows through the API proxy.
 */
class WhepLiveController(
    context: Context,
    private val http: OkHttpClient,
    private val endpoint: String,
    private val token: String,
    private val onFirstFrame: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var resourceURL: String? = null
    private var video: VideoTrack? = null
    private var audio: AudioTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var job: Job? = null
    private var closed = false
    private var muted = false
    private val iceGathered = CompletableDeferred<Unit>()
    private val main = Handler(Looper.getMainLooper())

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun bind(surface: SurfaceViewRenderer) {
        if (closed) return
        if (renderer === surface) return
        renderer?.let { previous -> video?.removeSink(previous) }
        renderer = surface
        surface.init(egl.eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                main.post { if (!closed) onFirstFrame() }
            }
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit
        })
        surface.setEnableHardwareScaler(true)
        video?.addSink(surface)
    }

    fun unbind(surface: SurfaceViewRenderer) {
        video?.removeSink(surface)
        if (renderer === surface) renderer = null
        surface.release()
    }

    fun start() {
        if (job != null || closed) return
        job = scope.launch {
            try {
                val connection = requireNotNull(factory.createPeerConnection(configuration(), observer()))
                peer = connection
                val offer = connection.createOfferAwait()
                connection.setLocalAwait(offer)
                // STUN can still be gathering while usable LAN candidates exist.
                // Bound gathering, not the lifetime of an otherwise valid offer.
                val localSdp = awaitUsableIceOffer(iceGathered) { connection.localDescription?.description }
                val answer = postOffer(localSdp)
                connection.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, answer))
            } catch (error: Throwable) {
                if (!closed) notifyFailure(error.message?.takeIf { it.isNotBlank() } ?: "WebRTC connection failed")
            }
        }
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        audio?.setEnabled(!muted)
    }

    fun close() {
        if (closed) return
        closed = true
        val url = resourceURL
        if (url != null) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
            })
        }
        renderer?.let { video?.removeSink(it) }
        renderer = null
        peer?.close()
        peer = null
        factory.dispose()
        egl.release()
        scope.cancel()
    }

    private fun configuration() = PeerConnection.RTCConfiguration(
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE && !iceGathered.isCompleted) iceGathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) {
            stream.videoTracks.firstOrNull()?.let(::attachVideo)
            stream.audioTracks.firstOrNull()?.let { audio = it }
        }
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<org.webrtc.MediaStream>) {
            when (val track = receiver.track()) {
                is VideoTrack -> attachVideo(track)
                is AudioTrack -> audio = track.also { it.setEnabled(!muted) }
            }
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> attachVideo(track)
                is AudioTrack -> audio = track.also { it.setEnabled(!muted) }
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED && !closed) notifyFailure("WebRTC connection failed")
        }
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) = Unit
    }

    private fun attachVideo(track: VideoTrack) {
        video?.let { existing -> renderer?.let(existing::removeSink) }
        video = track
        renderer?.let(track::addSink)
    }

    private fun notifyFailure(message: String) {
        main.post { if (!closed) onFailure(message) }
    }

    private suspend fun postOffer(sdp: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/sdp")
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (response.code != 201) throw IOException(backendMessage(body, response.code))
            resourceURL = response.header("Location")?.let { location -> response.request.url.resolve(location)?.toString() }
                ?: throw IOException("WebRTC session location missing")
            body
        }
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { continuation ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = continuation.resume(sdp)
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) = continuation.resumeWithException(IOException(error))
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
        })
    }

    private suspend fun PeerConnection.setLocalAwait(sdp: SessionDescription): Unit = suspendCancellableCoroutine { continuation ->
        setLocalDescription(setObserver(continuation), sdp)
    }

    private suspend fun PeerConnection.setRemoteAwait(sdp: SessionDescription): Unit = suspendCancellableCoroutine { continuation ->
        setRemoteDescription(setObserver(continuation), sdp)
    }

    private fun setObserver(continuation: kotlinx.coroutines.CancellableContinuation<Unit>) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = continuation.resume(Unit)
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = continuation.resumeWithException(IOException(error))
    }

    private fun backendMessage(body: String, status: Int): String = runCatching {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    }.getOrNull() ?: body.takeIf { it.isNotBlank() } ?: "WebRTC negotiation failed ($status)"

}
