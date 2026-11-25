package com.example.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object CallSessionBridge {
    @Volatile
    private var hangUpHandler: (() -> Unit)? = null

    fun registerHangUpHandler(handler: () -> Unit) {
        hangUpHandler = handler
    }

    fun clearHangUpHandler() {
        hangUpHandler = null
    }

    fun requestHangUp() {
        hangUpHandler?.invoke()
    }
}

class CallForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PulseCall:OngoingCallWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANG_UP -> {
                CallSessionBridge.requestHangUp()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                val roomCode = intent?.getStringExtra(EXTRA_ROOM_CODE).orEmpty()
                val peerName = intent?.getStringExtra(EXTRA_PEER_NAME)
                    ?.takeIf { it.isNotBlank() } ?: "PulseCall Peer"
                val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
                    ?.takeIf { it.isNotBlank() } ?: "Call running in background"

                val notification = buildOngoingCallNotification(
                    roomCode = roomCode,
                    peerName = peerName,
                    statusText = statusText
                )

                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    0
                }

                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        fgsType
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to promote CallForegroundService", e)
                }
                return START_STICKY
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout triggered; stopping ongoing call service cleanly")
        stopSelfSafely()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.call_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.call_notification_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildOngoingCallNotification(
        roomCode: String,
        peerName: String,
        statusText: String
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangUpIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_HANG_UP
        }
        val hangUpPendingIntent = PendingIntent.getService(
            this,
            REQ_HANG_UP,
            hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(if (roomCode.isNotBlank()) "$peerName · $roomCode" else peerName)
            .setImportant(true)
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(if (roomCode.isNotBlank()) "$peerName ($roomCode)" else peerName)
            .setContentText(statusText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addPerson(callerPerson)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(callerPerson, hangUpPendingIntent))
            .build()
    }

    companion object {
        private const val TAG = "CallForegroundService"
        private const val CHANNEL_ID = "pulsecall_ongoing_call"
        private const val NOTIFICATION_ID = 4102
        private const val REQ_CONTENT = 101
        private const val REQ_HANG_UP = 102
        private const val MAX_WAKELOCK_MS = 4 * 60 * 60 * 1000L

        private const val ACTION_START_OR_UPDATE = "com.example.call.ACTION_START_OR_UPDATE"
        private const val ACTION_STOP = "com.example.call.ACTION_STOP"
        private const val ACTION_HANG_UP = "com.example.call.ACTION_HANG_UP"

        private const val EXTRA_ROOM_CODE = "extra_room_code"
        private const val EXTRA_PEER_NAME = "extra_peer_name"
        private const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun startOrUpdate(
            context: Context,
            roomCode: String,
            peerName: String,
            statusText: String
        ) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, CallForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_ROOM_CODE, roomCode)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start CallForegroundService", e)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, CallForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to stop CallForegroundService", e)
            }
        }
    }
}
