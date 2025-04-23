package com.hfad.camera2

import android.content.Context
import android.content.SharedPreferences

class UserSessionManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "UserSession"
        private const val KEY_USERNAME = "username"
    }

    fun createSession(username: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    fun isLoggedIn(): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_USERNAME)
    }

    fun clearSession() {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    fun getLoggedInUsername(): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, null)
    }
}
