package com.esp.bora.data

import android.content.Context

object BiometricPreferences {
    private const val PREFERENCES_FILE = "bora_access_preferences"

    fun isEnabled(context: Context, uid: String): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getBoolean(preferenceKey(uid), false)

    fun setEnabled(context: Context, uid: String, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(preferenceKey(uid), enabled)
            .apply()
    }

    private fun preferenceKey(uid: String) = "biometric_enabled_$uid"
}
