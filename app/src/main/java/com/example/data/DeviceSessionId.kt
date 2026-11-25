package com.example.data

import android.content.Context
import java.util.UUID

object DeviceSessionId {
    private const val PREFS_NAME = "pulsecall_device_prefs"
    private const val KEY_DEVICE_ID = "device_session_id"

    @Volatile
    private var cachedId: String? = null

    fun getOrCreate(context: Context): String {
        cachedId?.let { return it }
        synchronized(this) {
            cachedId?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank() && existing.length in 4..64) {
                cachedId = existing
                return existing
            }
            val generated = "and-${UUID.randomUUID().toString().replace("-", "").take(20)}"
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            cachedId = generated
            return generated
        }
    }
}
