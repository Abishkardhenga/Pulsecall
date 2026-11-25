package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CallRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun ensureUserProfile(displayName: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            val safeName = displayName.trim().ifBlank { "PulseCall User" }.take(60)
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "platform" to "android",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                docRef.update(
                    mapOf(
                        "displayName" to safeName,
                        "platform" to "android",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    fun observeCallHistory(userId: String): Flow<List<CallHistoryEntry>> = flow {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || uid != userId) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$userId/call_history"
        emitAll(
            db.collection("users").document(userId).collection("call_history")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CallHistoryEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun logCallHistory(
        roomCode: String,
        peerName: String,
        peerPlatform: String,
        direction: CallDirection,
        status: CallRecordStatus,
        durationSeconds: Long
    ): Result<Unit> {
        val currentUid = auth.currentUser?.uid
        val path = "users/${currentUid ?: "unauthenticated"}/call_history"
        return try {
            val uid = requireUserId()
            val cleanRoomCode = roomCode.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(16)
            require(cleanRoomCode.length in 4..16) { "Invalid room code" }
            val safePeerName = peerName.trim().ifBlank { "iOS / Remote Peer" }.take(60)
            val safePlatform = when (peerPlatform.lowercase()) {
                "android", "ios" -> peerPlatform.lowercase()
                else -> "unknown"
            }
            val safeDuration = durationSeconds.coerceIn(0L, 86400L)
            val docRef = db.collection("users").document(uid).collection("call_history").document()
            val payload = mapOf(
                "userId" to uid,
                "roomCode" to cleanRoomCode,
                "peerName" to safePeerName,
                "peerPlatform" to safePlatform,
                "direction" to direction.wireValue,
                "status" to status.wireValue,
                "durationSeconds" to safeDuration,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteCallHistory(entryId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
        val path = "users/${currentUid ?: "unauthenticated"}/call_history/$entryId"
        return try {
            val uid = requireUserId()
            require(entryId.isNotBlank()) { "Entry ID cannot be blank" }
            db.collection("users").document(uid).collection("call_history")
                .document(entryId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun createCallRoom(
        roomCode: String,
        hostName: String,
        offerSdp: String
    ): Result<Unit> {
        val path = "calls/$roomCode"
        return try {
            val uid = requireUserId()
            require(roomCode.matches(Regex("^[a-zA-Z0-9_\\-]{4,16}$"))) { "Invalid room code format" }
            require(offerSdp.length in 10..20000) { "SDP offer size out of bounds" }
            val safeHostName = hostName.trim().ifBlank { "Android Host" }.take(60)
            val payload = mapOf(
                "hostUid" to uid,
                "hostName" to safeHostName,
                "hostPlatform" to "android",
                "status" to CallRoomStatus.WAITING.wireValue,
                "offerSdp" to offerSdp,
                "offerType" to "offer",
                "callerCandidates" to emptyList<Map<String, Any>>(),
                "calleeCandidates" to emptyList<Map<String, Any>>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("calls").document(roomCode).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun getCallRoom(roomCode: String): Result<CallRoom?> {
        val path = "calls/$roomCode"
        return try {
            requireUserId()
            require(roomCode.matches(Regex("^[a-zA-Z0-9_\\-]{4,16}$"))) { "Invalid room code format" }
            val snapshot = db.collection("calls").document(roomCode).get().await()
            if (!snapshot.exists()) {
                Result.success(null)
            } else {
                val room = snapshot.toObject(
                    CallRoom::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                Result.success(room)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun joinCallRoom(
        roomCode: String,
        guestName: String,
        answerSdp: String
    ): Result<Unit> {
        val path = "calls/$roomCode"
        return try {
            val uid = requireUserId()
            require(answerSdp.length in 10..20000) { "SDP answer size out of bounds" }
            val safeGuestName = guestName.trim().ifBlank { "Android Guest" }.take(60)
            db.collection("calls").document(roomCode).update(
                mapOf(
                    "guestUid" to uid,
                    "guestName" to safeGuestName,
                    "guestPlatform" to "android",
                    "answerSdp" to answerSdp,
                    "answerType" to "answer",
                    "status" to CallRoomStatus.CONNECTED.wireValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun appendIceCandidate(
        roomCode: String,
        isHost: Boolean,
        candidate: IceCandidatePayload
    ): Result<Unit> {
        val path = "calls/$roomCode"
        return try {
            requireUserId()
            val fieldName = if (isHost) "callerCandidates" else "calleeCandidates"
            db.collection("calls").document(roomCode).update(
                mapOf(
                    fieldName to FieldValue.arrayUnion(candidate.toFirestoreMap()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun endCallRoom(roomCode: String): Result<Unit> {
        val path = "calls/$roomCode"
        return try {
            requireUserId()
            db.collection("calls").document(roomCode).update(
                mapOf(
                    "status" to CallRoomStatus.ENDED.wireValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeCallRoom(roomCode: String): Flow<CallRoom?> = flow {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            emit(null)
            return@flow
        }
        val path = "calls/$roomCode"
        emitAll(
            db.collection("calls").document(roomCode)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        CallRoom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }
}
