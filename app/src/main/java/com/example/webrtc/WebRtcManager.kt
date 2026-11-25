package com.example.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.data.IceCandidatePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcManager(
    context: Context,
    private val onLocalIceCandidate: (IceCandidatePayload) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var previousAudioMode: Int = audioManager?.mode ?: AudioManager.MODE_NORMAL
    private var previousSpeakerState: Boolean = false

    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _iceConnectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val iceConnectionState: StateFlow<PeerConnection.IceConnectionState> = _iceConnectionState.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _hasLocalCamera = MutableStateFlow(false)
    val hasLocalCamera: StateFlow<Boolean> = _hasLocalCamera.asStateFlow()

    private val isReleased = AtomicBoolean(false)
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    init {
        ensureFactoryInitialized(appContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        configureCallAudio(speakerOn = true)
    }

    fun startLocalMedia() {
        if (isReleased.get()) return
        if (localAudioTrack == null) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory.createAudioTrack("ARDAMSa0", audioSource).apply {
                setEnabled(!_isMicMuted.value)
            }
        }

        if (_localVideoTrack.value == null) {
            val capturer = createCameraCapturer()
            if (capturer != null) {
                videoCapturer = capturer
                val helper = SurfaceTextureHelper.create("PulseCaptureThread", eglBase.eglBaseContext)
                surfaceTextureHelper = helper
                val vSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                videoSource = vSource
                capturer.initialize(helper, appContext, vSource.capturerObserver)
                try {
                    capturer.startCapture(1280, 720, 30)
                    _hasLocalCamera.value = true
                } catch (e: Exception) {
                    Log.w(TAG, "720p capture failed, falling back to 640x480", e)
                    try {
                        capturer.startCapture(640, 480, 24)
                        _hasLocalCamera.value = true
                    } catch (fallbackEx: Exception) {
                        Log.e(TAG, "Camera capture unavailable", fallbackEx)
                        _hasLocalCamera.value = false
                    }
                }
                val track = peerConnectionFactory.createVideoTrack("ARDAMSv0", vSource).apply {
                    setEnabled(_isCameraEnabled.value)
                }
                _localVideoTrack.value = track
            } else {
                _hasLocalCamera.value = false
            }
        }

        ensurePeerConnection()
    }

    private fun ensurePeerConnection(): PeerConnection {
        peerConnection?.let { return it }
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (newState != null && !isReleased.get()) {
                    _iceConnectionState.value = newState
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null && !isReleased.get()) {
                    onLocalIceCandidate(
                        IceCandidatePayload(
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid ?: "0",
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null && !isReleased.get()) {
                    _remoteVideoTrack.value = track
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack && !isReleased.get()) {
                    _remoteVideoTrack.value = track
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack && !isReleased.get()) {
                    _remoteVideoTrack.value = track
                }
            }
        }) ?: throw IllegalStateException("Failed to create PeerConnection")

        val streamIds = listOf("ARDAMS")
        localAudioTrack?.let { pc.addTrack(it, streamIds) }
        _localVideoTrack.value?.let { pc.addTrack(it, streamIds) }

        peerConnection = pc
        return pc
    }

    suspend fun createOffer(): SessionDescription {
        val pc = ensurePeerConnection()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null && cont.isActive) cont.resume(desc)
                    else if (cont.isActive) cont.resumeWithException(IllegalStateException("Null offer SDP"))
                }

                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "createOffer failed"))
                }
            }, constraints)
        }
        setLocalDescription(pc, offer)
        return offer
    }

    suspend fun createAnswer(remoteOfferSdp: String): SessionDescription {
        val pc = ensurePeerConnection()
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
        setRemoteDescription(pc, offerDesc)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val answer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createAnswer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null && cont.isActive) cont.resume(desc)
                    else if (cont.isActive) cont.resumeWithException(IllegalStateException("Null answer SDP"))
                }

                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "createAnswer failed"))
                }
            }, constraints)
        }
        setLocalDescription(pc, answer)
        return answer
    }

    suspend fun setRemoteAnswer(answerSdp: String) {
        val pc = ensurePeerConnection()
        if (isRemoteDescriptionSet) return
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        setRemoteDescription(pc, answerDesc)
    }

    fun addRemoteIceCandidate(payload: IceCandidatePayload) {
        if (isReleased.get()) return
        val candidate = IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
        val pc = peerConnection
        if (pc != null && isRemoteDescriptionSet) {
            pc.addIceCandidate(candidate)
        } else {
            synchronized(pendingRemoteIceCandidates) {
                pendingRemoteIceCandidates.add(candidate)
            }
        }
    }

    private suspend fun setLocalDescription(pc: PeerConnection, desc: SessionDescription) {
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setLocalDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "setLocalDescription failed"))
                }
            }, desc)
        }
    }

    private suspend fun setRemoteDescription(pc: PeerConnection, desc: SessionDescription) {
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    flushPendingCandidates(pc)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "setRemoteDescription failed"))
                }
            }, desc)
        }
    }

    private fun flushPendingCandidates(pc: PeerConnection) {
        synchronized(pendingRemoteIceCandidates) {
            pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
            pendingRemoteIceCandidates.clear()
        }
    }

    fun toggleMic() {
        val nextMuted = !_isMicMuted.value
        _isMicMuted.value = nextMuted
        localAudioTrack?.setEnabled(!nextMuted)
    }

    fun toggleCamera() {
        val nextEnabled = !_isCameraEnabled.value
        _isCameraEnabled.value = nextEnabled
        _localVideoTrack.value?.setEnabled(nextEnabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                _isFrontCamera.value = isFront
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(TAG, "Camera switch error: $errorDescription")
            }
        })
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        _isSpeakerOn.value = next
        setSpeakerphoneEnabled(next)
    }

    private fun configureCallAudio(speakerOn: Boolean) {
        val am = audioManager ?: return
        try {
            previousAudioMode = am.mode
            @Suppress("DEPRECATION")
            previousSpeakerState = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphoneEnabled(speakerOn)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to configure audio routing", e)
        }
    }

    private fun setSpeakerphoneEnabled(enabled: Boolean) {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speakerDevice = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        am.setCommunicationDevice(speakerDevice)
                    }
                } else {
                    val earpiece = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpiece != null) {
                        am.setCommunicationDevice(earpiece)
                    } else {
                        am.clearCommunicationDevice()
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = enabled
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch speakerphone", e)
        }
    }

    private fun restoreCallAudio() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = previousSpeakerState
            }
            am.mode = previousAudioMode
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore audio mode", e)
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                _isFrontCamera.value = true
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                _isFrontCamera.value = false
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        restoreCallAudio()
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PeerConnection", e)
        }
        peerConnection = null

        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping video capture", e)
        }
        try {
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing video capturer", e)
        }
        videoCapturer = null

        _localVideoTrack.value = null
        _remoteVideoTrack.value = null

        try {
            videoSource?.dispose()
            audioSource?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnectionFactory.dispose()
            eglBase.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WebRTC resources", e)
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val TAG = "WebRtcManager"
        private val isFactoryInitialized = AtomicBoolean(false)

        private fun ensureFactoryInitialized(appContext: Context) {
            if (isFactoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }
}
