package com.github.musicyou.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper for storing sync-related preferences like user name.
 */
object SyncPreferences {
    private const val PREFS_NAME = "sync_preferences"
    private const val KEY_USER_NAME = "user_name"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get the stored user name, or null if not set.
     */
    fun getUserName(context: Context): String? {
        return getPrefs(context).getString(KEY_USER_NAME, null)
    }
    
    /**
     * Save the user name.
     */
    fun setUserName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_USER_NAME, name).apply()
    }
    
    /**
     * Check if user name is set.
     */
    fun hasUserName(context: Context): Boolean {
        return !getUserName(context).isNullOrBlank()
    }
}
