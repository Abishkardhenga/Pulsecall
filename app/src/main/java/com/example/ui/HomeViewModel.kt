package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CallHistoryEntry
import com.example.data.CallRepository
import com.example.data.CallRoomStatus
import com.example.data.UiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom

class HomeViewModel(
    private val repository: CallRepository,
    private val currentUserId: String,
    private val userDisplayName: String
) : ViewModel() {

    val callHistoryState: StateFlow<UiState<List<CallHistoryEntry>>> =
        repository.observeCallHistory(currentUserId)
            .map<List<CallHistoryEntry>, UiState<List<CallHistoryEntry>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing call history", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Failed to load call history"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    private val _joinRoomCode = MutableStateFlow("")
    val joinRoomCode: StateFlow<String> = _joinRoomCode.asStateFlow()

    private val _isValidatingJoin = MutableStateFlow(false)
    val isValidatingJoin: StateFlow<Boolean> = _isValidatingJoin.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureUserProfile(userDisplayName)
        }
    }

    fun updateJoinRoomCode(raw: String) {
        val cleaned = raw.uppercase()
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(12)
        _joinRoomCode.value = cleaned
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun showMessage(msg: String) {
        _statusBanner.value = msg
    }

    fun generateRoomCode(): String {
        val random = SecureRandom()
        return buildString(6) {
            repeat(6) {
                append(ROOM_ALPHABET[random.nextInt(ROOM_ALPHABET.length)])
            }
        }
    }

    fun validateAndJoinRoom(
        overrideCode: String? = null,
        onRoomReady: (String) -> Unit
    ) {
        val code = (overrideCode ?: _joinRoomCode.value).trim().uppercase()
        if (code.length < 4 || !code.matches(Regex("^[A-Z0-9\\-]{4,16}$"))) {
            _statusBanner.value = "Enter a valid 4–12 character room code."
            return
        }
        if (_isValidatingJoin.value) return
        _isValidatingJoin.value = true

        viewModelScope.launch {
            val result = repository.getCallRoom(code)
            _isValidatingJoin.value = false
            result.fold(
                onSuccess = { room ->
                    when {
                        room == null -> {
                            _statusBanner.value = "No active room found for code $code."
                        }
                        CallRoomStatus.fromWire(room.status) == CallRoomStatus.ENDED -> {
                            _statusBanner.value = "Call room $code has already ended."
                        }
                        room.guestUid != null && room.guestUid != currentUserId -> {
                            _statusBanner.value = "Room $code already has two participants."
                        }
                        else -> {
                            onRoomReady(code)
                        }
                    }
                },
                onFailure = { error ->
                    _statusBanner.value = error.localizedMessage ?: "Unable to verify room $code."
                }
            )
        }
    }

    fun deleteHistoryEntry(entryId: String) {
        viewModelScope.launch {
            val result = repository.deleteCallHistory(entryId)
            result.onFailure {
                _statusBanner.value = it.localizedMessage ?: "Could not delete call entry."
            }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val STOP_TIMEOUT_MILLIS = 5000L
        private const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
