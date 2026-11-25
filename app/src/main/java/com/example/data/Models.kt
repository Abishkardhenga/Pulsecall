package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class CallDirection(val wireValue: String) {
    OUTGOING("outgoing"),
    INCOMING("incoming");

    companion object {
        fun fromWire(raw: String?): CallDirection =
            entries.firstOrNull { it.wireValue.equals(raw, ignoreCase = true) } ?: OUTGOING
    }
}

enum class CallRecordStatus(val wireValue: String) {
    COMPLETED("completed"),
    MISSED("missed"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(raw: String?): CallRecordStatus =
            entries.firstOrNull { it.wireValue.equals(raw, ignoreCase = true) } ?: COMPLETED
    }
}

enum class CallRoomStatus(val wireValue: String) {
    WAITING("waiting"),
    CONNECTED("connected"),
    ENDED("ended");

    companion object {
        fun fromWire(raw: String?): CallRoomStatus =
            entries.firstOrNull { it.wireValue.equals(raw, ignoreCase = true) } ?: WAITING
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val platform: String = "android",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallHistoryEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val roomCode: String = "",
    val peerName: String = "",
    val peerPlatform: String = "unknown",
    val direction: String = "outgoing",
    val status: String = "completed",
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class IceCandidatePayload(
    val candidate: String = "",
    val sdpMid: String = "0",
    val sdpMLineIndex: Int = 0
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "candidate" to candidate.take(1024),
        "sdpMid" to sdpMid.take(64),
        "sdpMLineIndex" to sdpMLineIndex.coerceIn(0, 16)
    )

    companion object {
        fun fromRawMap(map: Map<*, *>): IceCandidatePayload? {
            val cand = map["candidate"] as? String ?: return null
            if (cand.isBlank()) return null
            val mid = (map["sdpMid"] as? String).orEmpty()
            val index = (map["sdpMLineIndex"] as? Number)?.toInt() ?: 0
            return IceCandidatePayload(
                candidate = cand,
                sdpMid = mid,
                sdpMLineIndex = index
            )
        }
    }
}

@Keep
@IgnoreExtraProperties
data class CallRoom(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val hostDeviceId: String = "",
    val hostName: String = "",
    val hostPlatform: String = "android",
    val guestUid: String? = null,
    val guestDeviceId: String? = null,
    val guestName: String? = null,
    val guestPlatform: String? = null,
    val status: String = "waiting",
    val offerSdp: String = "",
    val offerType: String = "offer",
    val offerEpoch: Int = 1,
    val answerSdp: String? = null,
    val answerType: String? = null,
    val answerEpoch: Int = 0,
    val renegotiateEpoch: Int = 0,
    val callerCandidates: List<Map<String, Any>> = emptyList(),
    val calleeCandidates: List<Map<String, Any>> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class ActiveCallSummary(
    val roomCode: String,
    val isHost: Boolean,
    val activeDevicePlatform: String,
    val peerName: String,
    val status: CallRoomStatus
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

