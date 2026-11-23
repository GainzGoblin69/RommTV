package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import com.swordfish.libretrodroid.OfflineLedger
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline RetroAchievements (softcore only). A game played once online can later be played with no
 * connection: its achievement data and the sign-in are kept, unlocks earned offline are saved in order, and
 * they're sent to RetroAchievements when the connection is back, each with how long ago it was earned (the
 * way RetroAchievements accepts delayed unlocks).
 */
object OfflineAchievements {
    fun dir(context: Context) = File(context.applicationContext.filesDir, "retroachievements-offline")

    fun pendingCount(context: Context) = runCatching { OfflineLedger.pending(dir(context)).size }.getOrDefault(0)

    private val syncing = AtomicBoolean(false)

    data class Result(val sent: Int, val dropped: Int, val left: Int, val offline: Boolean)

    /** Sends saved unlocks (blocking; call off the main thread). Stops at the first connection problem. */
    fun sync(context: Context): Result {
        val folder = dir(context)
        val account = RetroAchievementsAccount.load(context) ?: return Result(0, 0, pendingCount(context), false)
        if (!syncing.compareAndSet(false, true)) return Result(0, 0, pendingCount(context), false)
        try {
            val entries = OfflineLedger.pending(folder).sortedBy { it.time }
            var sent = 0
            var dropped = 0
            for (entry in entries) {
                val seconds = ((System.currentTimeMillis() - entry.time) / 1000).coerceAtLeast(1)
                val answer =
                    try {
                        post(award(account, entry.id, entry.hash, seconds))
                    } catch (e: IOException) {
                        Timber.i("Offline unlocks: still no connection (${e.message})")
                        return Result(sent, dropped, entries.size - sent - dropped, true)
                    }
                val json = runCatching { JSONObject(answer) }.getOrNull()
                when {
                    json == null -> return Result(sent, dropped, entries.size - sent - dropped, true)
                    json.optBoolean("Success", false) -> sent++
                    json.optString("Error").contains("already", ignoreCase = true) -> sent++
                    else -> {
                        // Turned down for good (e.g. the achievement changed or was removed): don't keep retrying
                        Timber.w("Offline unlock ${entry.id} not accepted: ${json.optString("Error")}")
                        dropped++
                    }
                }
                OfflineLedger.remove(folder, setOf(entry.id))
            }
            return Result(sent, dropped, 0, false)
        } finally {
            syncing.set(false)
        }
    }

    /** In the background, when there's anything to send. */
    fun syncSoon(context: Context) {
        val app = context.applicationContext
        if (pendingCount(app) == 0) return
        Thread { runCatching { sync(app) } }.start()
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The same request the emulator makes for an unlock, with "earned N seconds ago" and its signature. */
    private fun award(
        account: RetroAchievementsAccount.Account,
        id: Int,
        hash: String,
        seconds: Long,
    ): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val signature = md5("$id${account.username}0$id$seconds")
        return "r=awardachievement&u=${enc(account.username)}&t=${enc(account.token)}&a=$id&h=0" +
            (if (hash.isNotBlank()) "&m=${enc(hash)}" else "") +
            "&o=$seconds&v=$signature"
    }

    private fun post(form: String): String {
        val connection = URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", RetroAchievementsAccount.product)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(form.toByteArray()) }
            val code = connection.responseCode
            if (code >= 500) throw IOException("RetroAchievements answered HTTP $code")
            return (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.reader().readText() } ?: ""
        } finally {
            connection.disconnect()
        }
    }
}
