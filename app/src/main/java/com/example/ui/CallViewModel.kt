package com.example.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.call.CallForegroundService
import com.example.call.CallSessionBridge
import com.example.call.CallSoundEffects
import com.example.data.CallDirection
import com.example.data.CallRecordStatus
import com.example.data.CallRepository
import com.example.data.CallRoom
import com.example.data.CallRoomStatus
import com.example.data.DeviceSessionId
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
    data object SwitchedDevice : CallPhase
    data class Error(val message: String) : CallPhase
}

class CallViewModel(
    private val repository: CallRepository,
    appContext: Context,
    private val currentUserId: String,
    private val userDisplayName: String,
    val roomCode: String,
    val isHost: Boolean,
    private val isTakeover: Boolean = false
) : ViewModel() {

    private val appCtx = appContext.applicationContext
    private val myDeviceId: String = DeviceSessionId.getOrCreate(appCtx)
    private val soundEffects = CallSoundEffects()

    private val _callPhase = MutableStateFlow<CallPhase>(CallPhase.RequestingPermissions)
    val callPhase: StateFlow<CallPhase> = _callPhase.asStateFlow()

    private val _peerName = MutableStateFlow("Waiting for peer")
    val peerName: StateFlow<String> = _peerName.asStateFlow()

    private val _peerPlatform = MutableStateFlow("unknown")
    val peerPlatform: StateFlow<String> = _peerPlatform.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _shouldExitCall = MutableStateFlow(false)
    val shouldExitCall: StateFlow<Boolean> = _shouldExitCall.asStateFlow()

    val isSoundEffectsEnabled: StateFlow<Boolean> = soundEffects.isSoundEnabled

    // Epoch & candidate tracking across WebRTC renegotiations
    private var activeOfferEpoch = 1
    private var lastAppliedAnswerEpoch = 0
    private var lastAppliedOfferEpoch = 0
    private var lastHandledRenegotiateEpoch = 0
    private var pendingGuestOfferMinEpoch: Int? = null
    private var isRenegotiating = false
    private var hasPlayedConnectedChimeForEpoch = -1

    @Volatile
    private var canSendIceCandidates = false
    private val pendingLocalIceCandidates = mutableListOf<IceCandidatePayload>()
    private var localCandidatesSent = 0
    private val processedRemoteCandidateKeys = mutableSetOf<String>()

    private val hasStartedSession = AtomicBoolean(false)
    private val hasEndedOrLogged = AtomicBoolean(false)

    private var roomObserverJob: Job? = null
    private var timerJob: Job? = null

    val webRtcManager: WebRtcManager = WebRtcManager(appCtx) { candidate ->
        onGeneratedLocalIceCandidate(candidate)
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
        CallSessionBridge.registerHangUpHandler {
            endCall {
                _shouldExitCall.value = true
            }
        }

        viewModelScope.launch {
            webRtcManager.iceConnectionState.collectLatest { iceState ->
                when (iceState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        val phase = _callPhase.value
                        if (phase !is CallPhase.PeerEnded &&
                            phase !is CallPhase.SwitchedDevice &&
                            phase !is CallPhase.Error
                        ) {
                            soundEffects.stopRingback()
                            val currentEpoch = if (isHost) activeOfferEpoch else lastAppliedOfferEpoch
                            if (hasPlayedConnectedChimeForEpoch != currentEpoch) {
                                hasPlayedConnectedChimeForEpoch = currentEpoch
                                soundEffects.playConnectedChime()
                            }
                            _callPhase.value = CallPhase.Connected
                            startTimerIfNeeded()
                            updateForegroundNotification()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (_callPhase.value is CallPhase.Connected) {
                            _callPhase.value = CallPhase.Connecting
                            updateForegroundNotification()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateForegroundNotification() {
        val statusText = when (_callPhase.value) {
            is CallPhase.WaitingForPeer -> "Ringing · Waiting for peer to join"
            is CallPhase.Connecting -> "Connecting audio & video…"
            is CallPhase.Connected -> "Ongoing call · Audio active in background"
            is CallPhase.Initializing -> "Starting call audio…"
            else -> return
        }
        CallForegroundService.startOrUpdate(
            context = appCtx,
            roomCode = roomCode,
            peerName = _peerName.value,
            statusText = statusText
        )
    }

    private fun onGeneratedLocalIceCandidate(candidate: IceCandidatePayload) {
        if (!canSendIceCandidates) {
            synchronized(pendingLocalIceCandidates) {
                if (pendingLocalIceCandidates.size < MAX_ICE_CANDIDATES) {
                    pendingLocalIceCandidates.add(candidate)
                }
            }
            return
        }
        sendIceCandidateIfUnderLimit(candidate)
    }

    private fun enableAndFlushLocalIceCandidates() {
        val buffered: List<IceCandidatePayload>
        synchronized(pendingLocalIceCandidates) {
            buffered = pendingLocalIceCandidates.toList()
            pendingLocalIceCandidates.clear()
            canSendIceCandidates = true
        }
        buffered.forEach { sendIceCandidateIfUnderLimit(it) }
    }

    private fun pauseAndClearIceState() {
        canSendIceCandidates = false
        synchronized(pendingLocalIceCandidates) {
            pendingLocalIceCandidates.clear()
        }
        localCandidatesSent = 0
        processedRemoteCandidateKeys.clear()
    }

    private fun sendIceCandidateIfUnderLimit(candidate: IceCandidatePayload) {
        if (localCandidatesSent >= MAX_ICE_CANDIDATES) return
        localCandidatesSent++
        viewModelScope.launch {
            repository.appendIceCandidate(roomCode, isHost, candidate)
        }
    }

    fun onPermissionsGranted() {
        if (!hasStartedSession.compareAndSet(false, true)) return
        _callPhase.value = CallPhase.Initializing
        CallForegroundService.startOrUpdate(
            context = appCtx,
            roomCode = roomCode,
            peerName = _peerName.value,
            statusText = "Starting call audio…"
        )
        viewModelScope.launch {
            try {
                webRtcManager.startLocalMedia()
                if (isHost) {
                    if (isTakeover) startAsHostTakeover() else startAsHost()
                } else {
                    startAsGuest()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start call session", e)
                soundEffects.stopRingback()
                CallForegroundService.stop(appCtx)
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
        pauseAndClearIceState()
        val offer = webRtcManager.createOffer()
        activeOfferEpoch = 1
        lastAppliedAnswerEpoch = 0
        lastHandledRenegotiateEpoch = 0
        val createResult = repository.createCallRoom(
            roomCode = roomCode,
            hostDeviceId = myDeviceId,
            hostName = userDisplayName,
            offerSdp = offer.description
        )
        createResult.fold(
            onSuccess = {
                enableAndFlushLocalIceCandidates()
                _callPhase.value = CallPhase.WaitingForPeer
                soundEffects.startRingback()
                updateForegroundNotification()
                observeRoomSignaling()
            },
            onFailure = { error ->
                soundEffects.stopRingback()
                CallForegroundService.stop(appCtx)
                _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Unable to create call room.")
            }
        )
    }

    private suspend fun startAsHostTakeover() {
        val roomResult = repository.getCallRoom(roomCode)
        val room = roomResult.getOrElse { error ->
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Failed to load call room.")
            return
        }
        if (room == null) {
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error("Room $roomCode was not found.")
            return
        }
        if (CallRoomStatus.fromWire(room.status) == CallRoomStatus.ENDED) {
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error("This call room has already ended.")
            return
        }
        if (room.hostUid != currentUserId) {
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error("Only the room host account can take over host control.")
            return
        }

        if (!room.guestName.isNullOrBlank()) {
            _peerName.value = room.guestName
        }
        if (!room.guestPlatform.isNullOrBlank()) {
            _peerPlatform.value = room.guestPlatform
        }

        pauseAndClearIceState()
        val nextOfferEpoch = room.offerEpoch + 1
        activeOfferEpoch = nextOfferEpoch
        lastAppliedAnswerEpoch = 0
        lastHandledRenegotiateEpoch = room.renegotiateEpoch

        val offer = webRtcManager.createOffer()
        val handoffResult = repository.handoffHostToThisDevice(
            roomCode = roomCode,
            hostDeviceId = myDeviceId,
            hostName = userDisplayName,
            offerSdp = offer.description,
            nextOfferEpoch = nextOfferEpoch
        )
        handoffResult.fold(
            onSuccess = {
                enableAndFlushLocalIceCandidates()
                soundEffects.playHandoffChime()
                if (room.guestUid != null) {
                    _callPhase.value = CallPhase.Connecting
                } else {
                    _callPhase.value = CallPhase.WaitingForPeer
                    soundEffects.startRingback()
                }
                updateForegroundNotification()
                observeRoomSignaling()
            },
            onFailure = { error ->
                soundEffects.stopRingback()
                CallForegroundService.stop(appCtx)
                _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Unable to switch host device.")
            }
        )
    }

    private suspend fun startAsGuest() {
        val roomResult = repository.getCallRoom(roomCode)
        val room = roomResult.getOrElse { error ->
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Failed to load call room.")
            return
        }
        if (room == null) {
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error("Room $roomCode was not found.")
            return
        }
        if (CallRoomStatus.fromWire(room.status) == CallRoomStatus.ENDED) {
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.Error("This call room has already ended.")
            return
        }

        _peerName.value = room.hostName.ifBlank { "Remote Host" }
        _peerPlatform.value = room.hostPlatform.ifBlank { "unknown" }
        _callPhase.value = CallPhase.Connecting
        updateForegroundNotification()

        val isTakingOverGuest = isTakeover || (room.guestUid == currentUserId)
        if (isTakingOverGuest && room.guestUid == currentUserId && room.guestDeviceId != myDeviceId) {
            // Request Host to generate a fresh WebRTC offer for this new guest device
            pauseAndClearIceState()
            pendingGuestOfferMinEpoch = room.offerEpoch
            lastAppliedOfferEpoch = room.offerEpoch
            soundEffects.playHandoffChime()
            val reqResult = repository.requestGuestHandoff(
                roomCode = roomCode,
                guestDeviceId = myDeviceId,
                guestName = userDisplayName,
                nextRenegotiateEpoch = room.renegotiateEpoch + 1
            )
            reqResult.fold(
                onSuccess = {
                    observeRoomSignaling()
                },
                onFailure = { error ->
                    CallForegroundService.stop(appCtx)
                    _callPhase.value = CallPhase.Error(error.localizedMessage ?: "Unable to switch guest device.")
                }
            )
            return
        }

        // Standard first-time guest join
        pauseAndClearIceState()
        soundEffects.playPeerJoiningChime()
        val offerEpoch = room.offerEpoch
        lastAppliedOfferEpoch = offerEpoch
        val answer = webRtcManager.createAnswer(room.offerSdp)
        ingestRemoteCandidates(room.callerCandidates)

        val joinResult = repository.joinCallRoom(
            roomCode = roomCode,
            guestDeviceId = myDeviceId,
            guestName = userDisplayName,
            answerSdp = answer.description,
            offerEpoch = offerEpoch
        )
        joinResult.fold(
            onSuccess = {
                enableAndFlushLocalIceCandidates()
                observeRoomSignaling()
            },
            onFailure = { error ->
                CallForegroundService.stop(appCtx)
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
                    soundEffects.stopRingback()
                    CallForegroundService.stop(appCtx)
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

        // 1. Detect if the same UID took over this role on another device (e.g. Web laptop)
        val supersededOnOtherDevice = if (isHost) {
            room.hostDeviceId.isNotBlank() && room.hostDeviceId != myDeviceId
        } else {
            room.guestUid == currentUserId &&
                !room.guestDeviceId.isNullOrBlank() &&
                room.guestDeviceId != myDeviceId
        }

        if (supersededOnOtherDevice) {
            handleSupersededByOtherDevice()
            return
        }

        // 2. Check terminal room end
        if (status == CallRoomStatus.ENDED && _callPhase.value !is CallPhase.PeerEnded) {
            soundEffects.playEndCallTone()
            CallForegroundService.stop(appCtx)
            _callPhase.value = CallPhase.PeerEnded
            timerJob?.cancel()
            logHistoryOnce(statusOverride = CallRecordStatus.COMPLETED)
            webRtcManager.release()
            return
        }

        // 3. Process role-specific signaling & peer handoffs
        if (isHost) {
            handleHostSnapshot(room)
        } else {
            handleGuestSnapshot(room)
        }
    }

    private suspend fun handleHostSnapshot(room: CallRoom) {
        if (!room.guestName.isNullOrBlank()) {
            _peerName.value = room.guestName
        }
        if (!room.guestPlatform.isNullOrBlank()) {
            _peerPlatform.value = room.guestPlatform
        }

        // Guest switched devices -> Host creates a fresh offerEpoch
        if (room.renegotiateEpoch > lastHandledRenegotiateEpoch && !isRenegotiating) {
            isRenegotiating = true
            try {
                lastHandledRenegotiateEpoch = room.renegotiateEpoch
                soundEffects.stopRingback()
                soundEffects.playHandoffChime()
                _callPhase.value = CallPhase.Connecting
                updateForegroundNotification()
                pauseAndClearIceState()
                webRtcManager.resetPeerConnectionForHandoff()

                val nextOfferEpoch = room.offerEpoch + 1
                activeOfferEpoch = nextOfferEpoch
                lastAppliedAnswerEpoch = 0

                val offer = webRtcManager.createOffer()
                val result = repository.handoffHostToThisDevice(
                    roomCode = roomCode,
                    hostDeviceId = myDeviceId,
                    hostName = userDisplayName,
                    offerSdp = offer.description,
                    nextOfferEpoch = nextOfferEpoch
                )
                result.onSuccess {
                    enableAndFlushLocalIceCandidates()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed host renegotiation for guest handoff", e)
            } finally {
                isRenegotiating = false
            }
            return
        }

        // Apply guest answer matching our active offerEpoch
        if (!room.answerSdp.isNullOrBlank() &&
            room.answerEpoch == activeOfferEpoch &&
            room.answerEpoch > lastAppliedAnswerEpoch
        ) {
            lastAppliedAnswerEpoch = room.answerEpoch
            soundEffects.stopRingback()
            if (_callPhase.value is CallPhase.WaitingForPeer) {
                soundEffects.playPeerJoiningChime()
            }
            _callPhase.value = CallPhase.Connecting
            updateForegroundNotification()
            try {
                webRtcManager.setRemoteAnswer(room.answerSdp)
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting remote answer", e)
            }
        }

        if (room.answerEpoch == activeOfferEpoch) {
            ingestRemoteCandidates(room.calleeCandidates)
        }
    }

    private suspend fun handleGuestSnapshot(room: CallRoom) {
        if (room.hostName.isNotBlank()) {
            _peerName.value = room.hostName
        }
        if (room.hostPlatform.isNotBlank()) {
            _peerPlatform.value = room.hostPlatform
        }

        val minEpoch = pendingGuestOfferMinEpoch
        val waitingForRenegotiatedOffer = minEpoch != null && room.offerEpoch <= minEpoch

        if (waitingForRenegotiatedOffer) {
            return
        }

        // Host switched devices OR responded to our guest handoff with a higher offerEpoch
        if (room.offerEpoch > lastAppliedOfferEpoch &&
            room.answerEpoch < room.offerEpoch &&
            !isRenegotiating
        ) {
            isRenegotiating = true
            try {
                pendingGuestOfferMinEpoch = null
                lastAppliedOfferEpoch = room.offerEpoch
                soundEffects.playHandoffChime()
                _callPhase.value = CallPhase.Connecting
                updateForegroundNotification()
                pauseAndClearIceState()
                webRtcManager.resetPeerConnectionForHandoff()

                val answer = webRtcManager.createAnswer(room.offerSdp)
                ingestRemoteCandidates(room.callerCandidates)

                val result = repository.submitRenegotiatedAnswer(
                    roomCode = roomCode,
                    guestDeviceId = myDeviceId,
                    guestName = userDisplayName,
                    answerSdp = answer.description,
                    offerEpoch = room.offerEpoch
                )
                result.onSuccess {
                    enableAndFlushLocalIceCandidates()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed guest renegotiation during handoff", e)
            } finally {
                isRenegotiating = false
            }
            return
        }

        if (room.offerEpoch == lastAppliedOfferEpoch) {
            ingestRemoteCandidates(room.callerCandidates)
        }
    }

    private fun handleSupersededByOtherDevice() {
        if (_callPhase.value is CallPhase.SwitchedDevice) return
        timerJob?.cancel()
        roomObserverJob?.cancel()
        pauseAndClearIceState()
        soundEffects.playHandoffChime()
        CallForegroundService.stop(appCtx)
        logHistoryOnce(statusOverride = CallRecordStatus.COMPLETED)
        // Crucial: do NOT call endCallRoom() so the room stays live for the new device
        webRtcManager.release()
        _callPhase.value = CallPhase.SwitchedDevice
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

    fun toggleMic() {
        webRtcManager.toggleMic()
        soundEffects.playTogglePip(active = !webRtcManager.isMicMuted.value)
    }

    fun toggleCamera() {
        webRtcManager.toggleCamera()
        soundEffects.playTogglePip(active = webRtcManager.isCameraEnabled.value)
    }

    fun switchCamera() {
        webRtcManager.switchCamera()
        soundEffects.playTogglePip(active = true)
    }

    fun toggleSpeaker() {
        webRtcManager.toggleSpeaker()
        soundEffects.playTogglePip(active = webRtcManager.isSpeakerOn.value)
    }

    fun toggleSoundEffects() {
        soundEffects.toggleSoundEnabled()
    }

    fun endCall(onComplete: () -> Unit) {
        if (_callPhase.value is CallPhase.SwitchedDevice) {
            timerJob?.cancel()
            roomObserverJob?.cancel()
            CallForegroundService.stop(appCtx)
            webRtcManager.release()
            onComplete()
            return
        }
        timerJob?.cancel()
        roomObserverJob?.cancel()
        soundEffects.playEndCallTone()
        CallForegroundService.stop(appCtx)
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
        CallSessionBridge.clearHangUpHandler()
        timerJob?.cancel()
        roomObserverJob?.cancel()
        soundEffects.release()
        CallForegroundService.stop(appCtx)
        webRtcManager.release()
    }

    companion object {
        private const val TAG = "CallViewModel"
        private const val MAX_ICE_CANDIDATES = 40
    }
}
