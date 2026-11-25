package com.example.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CallDirection
import com.example.data.CallRecordStatus
import com.example.data.CallRepository
import com.example.data.CallRoom
import com.example.data.CallRoomStatus
import com.example.data.IceCandidatePayload
import com.example.webrtc.WebRtcManager
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CallPhase {
    data object RequestingPermissions : CallPhase
    data object Initializing : CallPhase
    data object WaitingForPeer : CallPhase
    data object Connecting : CallPhase
    data object Connected : CallPhase
    data object PeerEnded : CallPhase
    data class Error(val message: String) : CallPhase
}

class CallViewModel(
    private val repository: CallRepository,
    appContext: Context,
    private val currentUserId: String,
    private val userDisplayName: String,
    val roomCode: String,
    val isHost: Boolean
) : ViewModel() {

    private val _callPhase = MutableStateFlow<CallPhase>(CallPhase.RequestingPermissions)
    val callPhase: StateFlow<CallPhase> = _callPhase.asStateFlow()

    private val _peerName = MutableStateFlow("Waiting for peer")
    val peerName: StateFlow<String> = _peerName.asStateFlow()

    private val _peerPlatform = MutableStateFlow("unknown")
    val peerPlatform: StateFlow<String> = _peerPlatform.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val sentCandidatesCount = AtomicBoolean(false)
    private var localCandidatesSent = 0
    private val processedRemoteCandidateKeys = mutableSetOf<String>()
    private val hasStartedSession = AtomicBoolean(false)
    private val hasEndedOrLogged = AtomicBoolean(false)

    private var roomObserverJob: Job? = null
    private var timerJob: Job? = null

    val webRtcManager: WebRtcManager = WebRtcManager(appContext) { candidate ->
        if (localCandidatesSent < MAX_ICE_CANDIDATES) {
            localCandidatesSent++
            viewModelScope.launch {
                repository.appendIceCandidate(roomCode, isHost, candidate)
            }
        }
    }

    val eglBase: EglBase get() = webRtcManager.eglBase
    val localVideoTrack: StateFlow<VideoTrack?> = webRtcManager.localVideoTrack
    val remoteVideoTrack: StateFlow<VideoTrack?> = webRtcManager.remoteVideoTrack
    val isMicMuted: StateFlow<Boolean> = webRtcManager.isMicMuted
    val isCameraEnabled: StateFlow<Boolean> = webRtcManager.isCameraEnabled
    val isFrontCamera: StateFlow<Boolean> = webRtcManager.isFrontCamera
    val isSpeakerOn: StateFlow<Boolean> = webRtcManager.isSpeakerOn
    val hasLocalCamera: StateFlow<Boolean> = webRtcManager.hasLocalCamera

    init {
        viewModelScope.launch {
            webRtcManager.iceConnectionState.collectLatest { iceState ->
                when (iceState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        if (_callPhase.value !is CallPhase.PeerEnded && _callPhase.value !is CallPhase.Error) {
                            _callPhase.value = CallPhase.Connected
                            startTimerIfNeeded()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (_callPhase.value is CallPhase.Connected) {
                            _callPhase.value = CallPhase.Connecting
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onPermissionsGranted() {
        if (!hasStartedSession.compareAndSet(false, true)) return
        _callPhase.value = CallPhase.Initializing
        viewModelScope.launch {
            try {
                webRtcManager.startLocalMedia()
                if (isHost) {
                    startAsHost()
                } else {
                    startAsGuest()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start call session", e)
                _callPhase.value = CallPhase.Error(e.localizedMessage ?: "Failed to start WebRTC call.")
            }
        }
    }

    fun onPermissionsDenied() {
        if (_callPhase.value is CallPhase.RequestingPermissions) {
            _callPhase.value = CallPhase.RequestingPermissions
        }
    }

    private suspend fun startAsHost() {
        val offer = webRtcManager.createOffer()
        val createResult = repository.createCallRoom(
            roomCode = roomCode,
            hostName = userDisplayName,
            offerSdp = offer.description
        )
        createResult.fold(
            onSuccess = {
                _callPhase.value = CallPhase.WaitingForPeer
                observeRoomSignaling()
            },
            onFailure = { error ->
                _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Unable to create call room.")
            }
        )
    }

    private suspend fun startAsGuest() {
        val roomResult = repository.getCallRoom(roomCode)
        val room = roomResult.getOrElse { error ->
            _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Failed to load call room.")
            return
        }
        if (room == null) {
            _callPhase.value = CallPhase.Error("Room $roomCode was not found.")
            return
        }
        if (CallRoomStatus.fromWire(room.status) == CallRoomStatus.ENDED) {
            _callPhase.value = CallPhase.Error("This call room has already ended.")
            return
        }

        _peerName.value = room.hostName.ifBlank { "Remote Host" }
        _peerPlatform.value = room.hostPlatform.ifBlank { "unknown" }
        _callPhase.value = CallPhase.Connecting

        val answer = webRtcManager.createAnswer(room.offerSdp)
        ingestRemoteCandidates(room.callerCandidates)

        val joinResult = repository.joinCallRoom(
            roomCode = roomCode,
            guestName = userDisplayName,
            answerSdp = answer.description
        )
        joinResult.fold(
            onSuccess = {
                observeRoomSignaling()
            },
            onFailure = { error ->
                _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Unable to join call room.")
            }
        )
    }

    private fun observeRoomSignaling() {
        roomObserverJob?.cancel()
        roomObserverJob = viewModelScope.launch {
            repository.observeCallRoom(roomCode)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Room signaling error", rootError)
                    _callPhase.value = CallPhase.Error(rootError.localizedMessage ?: "Signaling connection lost.")
                }
                .collect { room ->
                    if (room == null) return@collect
                    handleRoomSnapshot(room)
                }
        }
    }

    private suspend fun handleRoomSnapshot(room: CallRoom) {
        val status = CallRoomStatus.fromWire(room.status)
        if (isHost) {
            if (!room.guestName.isNullOrBlank()) {
                _peerName.value = room.guestName
            }
            if (!room.guestPlatform.isNullOrBlank()) {
                _peerPlatform.value = room.guestPlatform
            }
            if (!room.answerSdp.isNullOrBlank() && !sentCandidatesCount.getAndSet(true)) {
                _callPhase.value = CallPhase.Connecting
                try {
                    webRtcManager.setRemoteAnswer(room.answerSdp)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed setting remote answer", e)
                }
            }
            ingestRemoteCandidates(room.calleeCandidates)
        } else {
            if (room.hostName.isNotBlank()) {
                _peerName.value = room.hostName
            }
            if (room.hostPlatform.isNotBlank()) {
                _peerPlatform.value = room.hostPlatform
            }
            ingestRemoteCandidates(room.callerCandidates)
        }

        if (status == CallRoomStatus.ENDED && _callPhase.value !is CallPhase.PeerEnded) {
            _callPhase.value = CallPhase.PeerEnded
            timerJob?.cancel()
            logHistoryOnce(statusOverride = CallRecordStatus.COMPLETED)
            webRtcManager.release()
        }
    }

    private fun ingestRemoteCandidates(rawList: List<Map<String, Any>>) {
        rawList.forEach { map ->
            val payload = IceCandidatePayload.fromRawMap(map) ?: return@forEach
            val dedupeKey = "${payload.sdpMid}|${payload.sdpMLineIndex}|${payload.candidate}"
            if (processedRemoteCandidateKeys.add(dedupeKey)) {
                webRtcManager.addRemoteIceCandidate(payload)
            }
        }
    }

    private fun startTimerIfNeeded() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                _elapsedSeconds.value += 1L
            }
        }
    }

    fun toggleMic() = webRtcManager.toggleMic()
    fun toggleCamera() = webRtcManager.toggleCamera()
    fun switchCamera() = webRtcManager.switchCamera()
    fun toggleSpeaker() = webRtcManager.toggleSpeaker()

    fun endCall(onComplete: () -> Unit) {
        timerJob?.cancel()
        roomObserverJob?.cancel()
        val wasConnected = _elapsedSeconds.value > 0L || _callPhase.value is CallPhase.Connected
        val finalStatus = if (wasConnected) CallRecordStatus.COMPLETED else CallRecordStatus.CANCELLED

        if (hasStartedSession.get()) {
            logHistoryOnce(statusOverride = finalStatus)
            CoroutineScope(NonCancellable + Dispatchers.IO).launch {
                repository.endCallRoom(roomCode)
            }
        }
        webRtcManager.release()
        onComplete()
    }

    private fun logHistoryOnce(statusOverride: CallRecordStatus) {
        if (!hasEndedOrLogged.compareAndSet(false, true)) return
        val duration = _elapsedSeconds.value
        val direction = if (isHost) CallDirection.OUTGOING else CallDirection.INCOMING
        val peer = _peerName.value.let {
            if (it == "Waiting for peer") "Unjoined Room" else it
        }
        val platform = _peerPlatform.value
        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            repository.logCallHistory(
                roomCode = roomCode,
                peerName = peer,
                peerPlatform = platform,
                direction = direction,
                status = statusOverride,
                durationSeconds = duration
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        roomObserverJob?.cancel()
        webRtcManager.release()
    }

    companion object {
        private const val TAG = "CallViewModel"
        private const val MAX_ICE_CANDIDATES = 40
    }
}
