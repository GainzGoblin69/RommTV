package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.os.Build
import com.swordfish.lemuroid.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The RetroAchievements account this device earns achievements with. Only the username and RetroAchievements'
 * sign-in token are kept (never the password). Stored as a small file because games run in their own process.
 */
object RetroAchievementsAccount {
    data class Account(val username: String, val token: String, val enabled: Boolean)

    class SignInException(message: String) : Exception(message)

    private const val FILE = "retroachievements.json"

    /** "RomMTV/1.0.28 (Android 12)": RetroAchievements asks every app to identify itself with a name and a numeric version. */
    val product: String
        get() = "RomMTV/1.0.${BuildConfig.VERSION_CODE} (Android ${Build.VERSION.RELEASE})"

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun load(context: Context): Account? =
        runCatching {
            val f = file(context)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            Account(o.getString("username"), o.getString("token"), o.optBoolean("enabled", true))
        }.getOrNull()

    private fun save(
        context: Context,
        account: Account,
    ) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(
            JSONObject()
                .put("username", account.username)
                .put("token", account.token)
                .put("enabled", account.enabled)
                .toString(),
        )
        tmp.renameTo(f)
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        load(context)?.let { save(context, it.copy(enabled = enabled)) }
    }

    fun signOut(context: Context) {
        file(context).delete()
    }

    /**
     * The ids of the achievements you've unlocked in a game, straight from RetroAchievements (blocking).
     * Softcore unlocks include hardcore ones. Null if it couldn't be fetched.
     */
    fun unlockedIds(
        account: Account,
        gameId: Int,
    ): Set<Int>? =
        runCatching {
            val form =
                "r=unlocks&u=" + URLEncoder.encode(account.username, "UTF-8") +
                    "&t=" + URLEncoder.encode(account.token, "UTF-8") +
                    "&g=$gameId&h=0"
            val json = JSONObject(post(form))
            if (!json.optBoolean("Success", false)) return null
            val ids = json.optJSONArray("UserUnlocks") ?: return emptySet()
            (0 until ids.length()).map { ids.getInt(it) }.toSet()
        }.getOrNull()

    private fun post(form: String): String {
        val connection = URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", product)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(form.toByteArray()) }
            val code = connection.responseCode
            return (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.reader().readText() } ?: ""
        } finally {
            connection.disconnect()
        }
    }

    /** Signs in with RetroAchievements (blocking; call off the main thread). Keeps the token, not the password. */
    fun signIn(
        context: Context,
        username: String,
        password: String,
    ): Account {
        val connection = URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", product)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form =
                "r=login2&u=" + URLEncoder.encode(username.trim(), "UTF-8") +
                    "&p=" + URLEncoder.encode(password, "UTF-8")
            connection.outputStream.use { it.write(form.toByteArray()) }

            val code = connection.responseCode
            val text = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.reader().readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw SignInException("RetroAchievements didn't answer properly (HTTP $code). Try again in a bit.")
            if (!json.optBoolean("Success", false)) {
                val error = json.optString("Error").ifBlank { "Sign in didn't work (HTTP $code)." }
                throw SignInException(error)
            }
            val account =
                Account(
                    username = json.optString("User").ifBlank { username.trim() },
                    token = json.getString("Token"),
                    enabled = true,
                )
            save(context, account)
            return account
        } finally {
            connection.disconnect()
        }
    }
}
