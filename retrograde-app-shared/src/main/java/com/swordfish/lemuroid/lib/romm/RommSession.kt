package com.swordfish.lemuroid.lib.romm

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Where the RomM server is and the token this TV signed in with.
 * Read from both the main process and the game process, so it always re-reads the file.
 */
class RommSession(context: Context) {
    private val appContext = context.applicationContext

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs().getString(KEY_SERVER, null) ?: DEFAULT_SERVER
        set(value) = prefs().edit().putString(KEY_SERVER, normalizeServer(value)).apply()

    val token: String?
        get() = prefs().getString(KEY_TOKEN, null)

    val deviceId: String?
        get() = prefs().getString(KEY_DEVICE_ID, null)

    val isSignedIn: Boolean
        get() = !token.isNullOrBlank()

    /** A stable id for this TV, sent when signing in so RomM lists it as one device. */
    val clientIdentifier: String
        get() {
            prefs().getString(KEY_CLIENT_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs().edit().putString(KEY_CLIENT_ID, id).commit()
            return id
        }

    fun signIn(
        server: String,
        token: String,
        deviceId: String?,
    ) {
        prefs().edit()
            .putString(KEY_SERVER, normalizeServer(server))
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .commit()
    }

    fun signOut() {
        prefs().edit().remove(KEY_TOKEN).remove(KEY_DEVICE_ID).commit()
    }

    companion object {
        private const val PREFS = "romm_session"
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CLIENT_ID = "client_id"

        const val DEFAULT_SERVER = "https://romm.alice99.box.ca"

        fun normalizeServer(value: String): String {
            var s = value.trim().trimEnd('/')
            if (s.isEmpty()) return DEFAULT_SERVER
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s
        }
    }
}
