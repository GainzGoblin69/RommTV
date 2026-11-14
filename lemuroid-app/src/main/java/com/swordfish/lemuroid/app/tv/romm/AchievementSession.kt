package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What happened with RetroAchievements in the last game played on this device: each step (sign-in, game
 * recognised, memory found, checking frames, unlocks) plus rcheevos' own log. Written by the game, shown on the
 * game page and in Settings → RetroAchievements, so a game that didn't unlock anything says why.
 */
object AchievementSession {
    data class Session(
        val gameUri: String,
        val gameTitle: String,
        val startedAt: Long,
        val steps: List<String>,
        val unlocks: List<String>,
        val problem: String?,
        val log: List<String>,
    ) {
        /** One line for the game page. */
        val summary: String
            get() =
                problem
                    ?: buildString {
                        append(steps.lastOrNull() ?: "Starting…")
                        if (unlocks.isNotEmpty()) append(" · ${unlocks.size} unlocked")
                    }
    }

    private const val FILE = "retroachievements-session.json"
    private const val MAX_LOG = 300

    private var current: Session? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun read(context: Context): Session? =
        runCatching {
            val f = file(context)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Session(
                gameUri = o.getString("game"),
                gameTitle = o.optString("title"),
                startedAt = o.optLong("started"),
                steps = list("steps"),
                unlocks = list("unlocks"),
                problem = o.optString("problem").ifBlank { null },
                log = list("log"),
            )
        }.getOrNull()

    private fun write(
        context: Context,
        session: Session,
    ) {
        current = session
        runCatching {
            val o =
                JSONObject()
                    .put("game", session.gameUri)
                    .put("title", session.gameTitle)
                    .put("started", session.startedAt)
                    .put("steps", JSONArray(session.steps))
                    .put("unlocks", JSONArray(session.unlocks))
                    .put("problem", session.problem ?: "")
                    .put("log", JSONArray(session.log.takeLast(MAX_LOG)))
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    fun begin(
        context: Context,
        gameUri: String,
        gameTitle: String,
        firstStep: String,
    ) {
        write(context, Session(gameUri, gameTitle, System.currentTimeMillis(), listOf(firstStep), emptyList(), null, emptyList()))
    }

    /** The game started without achievements, and why. */
    fun skipped(
        context: Context,
        gameUri: String,
        gameTitle: String,
        reason: String,
    ) {
        write(context, Session(gameUri, gameTitle, System.currentTimeMillis(), emptyList(), emptyList(), reason, emptyList()))
    }

    fun step(
        context: Context,
        text: String,
    ) {
        val s = current ?: return
        write(context, s.copy(steps = s.steps + text))
    }

    fun problem(
        context: Context,
        text: String,
    ) {
        val s = current ?: return
        write(context, s.copy(problem = text))
    }

    fun unlocked(
        context: Context,
        title: String,
    ) {
        val s = current ?: return
        write(context, s.copy(unlocks = s.unlocks + title))
    }

    fun log(
        context: Context,
        line: String,
    ) {
        val s = current ?: return
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(System.currentTimeMillis())
        write(context, s.copy(log = (s.log + "$time $line").takeLast(MAX_LOG)))
    }
}
