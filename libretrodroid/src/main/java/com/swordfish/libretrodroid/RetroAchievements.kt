package com.swordfish.libretrodroid

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.security.MessageDigest
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * RetroAchievements while a game runs. The emulator checks achievements every frame in native code
 * (rcheevos); this side does the network requests for it and passes events to the app.
 *
 * Call [start] once the game is running and [stop] when it closes (closing the game also stops it).
 */
object RetroAchievements {
    private const val TAG = "RetroAchievements"

    /** What happened, for the app to show. All calls arrive on the main thread. */
    interface Listener {
        /** Signed in and the game was recognised: [summary] is "X of Y achievements unlocked". */
        fun onGameLoaded(title: String, summary: String, achievementCount: Int, imageUrl: String?)

        /** [id] is RetroAchievements' id for the achievement. */
        fun onUnlocked(id: Int, title: String, description: String, points: Int, badgeUrl: String?)

        fun onMastered(title: String, imageUrl: String?)

        /** Something kept achievements from working for this game (bad sign-in, unknown game version…). */
        fun onProblem(kind: String, message: String?)

        /** Unlocks are waiting for the internet ([waiting] = true) or have all been sent (false). */
        fun onConnection(waiting: Boolean)

        /** Every event, including rcheevos' log lines ("log"), for diagnostics. */
        fun onEvent(
            kind: String,
            title: String?,
            description: String?,
            points: Int,
        ) {}
    }

    @Volatile
    var listener: Listener? = null

    @Volatile
    private var userAgent: String = "LibretroDroid/1.0"

    /**
     * Where offline data lives (set by the app). With it, a game played once online can be played offline in
     * softcore: its achievement data and the sign-in are kept, and unlocks earned offline go into a ledger
     * ([OfflineLedger]) that the app sends to RetroAchievements later, with how long ago each was earned.
     */
    @Volatile
    var offlineDir: File? = null

    /** True once this session has fallen back to offline play. */
    @Volatile
    var playingOffline = false
        private set

    private val network = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    init {
        System.loadLibrary("libretrodroid")
    }

    /**
     * Starts achievements for the running game.
     * [product] is the app's name and version for the User-Agent, e.g. "RomMTV/28 (Android 12)".
     * [consoleId] is RetroAchievements' console number; [gamePath] is the game file the core loaded.
     */
    /** Called when a game starts, so a session can tell it fell back to offline play. */
    private fun resetOffline() {
        playingOffline = false
    }

    fun start(
        product: String,
        username: String,
        token: String,
        consoleId: Int,
        gamePath: String,
    ): String {
        userAgent = product
        resetOffline()
        val clause = nativeStart(product, username, token, consoleId, gamePath)
        check(clause != "BRIDGE_FAIL") { "the emulator couldn't call back into the app" }
        if (clause.isNotBlank()) userAgent = "$product $clause"
        return userAgent
    }

    fun stop() {
        nativeStop()
    }

    /** What RetroAchievements shows for the game right now (e.g. "Level 3, 2 lives"), or "" if nothing. */
    fun richPresence(): String = runCatching { nativeRichPresence() }.getOrDefault("")

    @JvmStatic
    private external fun nativeRichPresence(): String

    /** The game's achievements as JSON ({game, total, unlocked, buckets:[{label, items:[...]}]}), or "" if none are loaded. */
    fun achievementList(): String = runCatching { nativeAchievementList()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()

    @JvmStatic
    private external fun nativeAchievementList(): ByteArray?

    @JvmStatic
    private external fun nativeStart(
        userAgent: String,
        username: String,
        token: String,
        consoleId: Int,
        gamePath: String,
    ): String

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeServerResponse(
        handle: Long,
        body: ByteArray?,
        status: Int,
    )

    /** Called from native code: rcheevos needs this request made. The answer goes back through nativeServerResponse. */
    @JvmStatic
    fun serverCall(
        handle: Long,
        url: String,
        postData: String?,
        contentType: String?,
    ) {
        network.execute {
            var body: ByteArray? = null
            var status = RETRYABLE_CLIENT_ERROR
            val params = parseForm(postData ?: url.substringAfter('?', ""))
            val kind = params["r"].orEmpty()
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                if (postData != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(postData.toByteArray()) }
                }
                status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                body =
                    stream?.use { input ->
                        val out = ByteArrayOutputStream()
                        input.copyTo(out)
                        out.toByteArray()
                    }
                connection.disconnect()
                if (status == 200 && body != null) remember(kind, params, body)
            } catch (e: IOException) {
                Log.w(TAG, "Request failed (no connection?): ${e.message}")
                val offline = offlineAnswer(kind, params)
                if (offline != null) {
                    body = offline
                    status = 200
                } else {
                    body = null
                    status = RETRYABLE_CLIENT_ERROR
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Request failed: ${e.message}")
                body = null
                status = RETRYABLE_CLIENT_ERROR
            }
            nativeServerResponse(handle, body, status)
        }
    }

    /** Called from native code with events from rcheevos. */
    @JvmStatic
    fun onMessage(
        kind: String,
        title: String?,
        description: String?,
        points: Int,
        imageUrl: String?,
    ) {
        main.post {
            val l = listener ?: return@post
            l.onEvent(kind, title, description, points)
            if (kind.startsWith("unlocked")) {
                l.onUnlocked(kind.substringAfter('#', "0").toIntOrNull() ?: 0, title ?: "", description ?: "", points, imageUrl)
                return@post
            }
            when (kind) {
                "game_loaded" -> l.onGameLoaded(title ?: "", description ?: "", points, imageUrl)
                "mastered" -> l.onMastered(title ?: "", imageUrl)
                "disconnected" -> l.onConnection(true)
                "reconnected" -> l.onConnection(false)
                "log", "login_ok", "game_info", "memory", "processing" -> Unit
                else -> l.onProblem(kind, description)
            }
        }
    }

    private const val RETRYABLE_CLIENT_ERROR = -2

    /** Requests whose answers are kept for offline play (sign-in, the game's achievement data, the session). */
    private val CACHED = setOf("login2", "gameid", "patch", "achievementsets", "startsession")

    private fun parseForm(form: String): Map<String, String> =
        form.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to runCatching { URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()

    /** One file per request, named by what's asked (not the token, which can change). */
    private fun cacheFile(
        kind: String,
        params: Map<String, String>,
    ): File? {
        val dir = offlineDir ?: return null
        val key = params.filterKeys { it != "t" && it != "p" }.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(File(dir, "cache").apply { mkdirs() }, "$kind-$name.json")
    }

    private fun remember(
        kind: String,
        params: Map<String, String>,
        body: ByteArray,
    ) {
        if (kind !in CACHED) return
        // Only good answers are worth keeping
        if (runCatching { JSONObject(String(body)).optBoolean("Success", true) }.getOrDefault(false).not()) return
        runCatching { cacheFile(kind, params)?.writeBytes(body) }
    }

    /** What to answer without a connection, or null to let it fail as before. */
    private fun offlineAnswer(
        kind: String,
        params: Map<String, String>,
    ): ByteArray? {
        offlineDir ?: return null
        val answer =
            when (kind) {
                in CACHED -> {
                    val cached = cacheFile(kind, params)?.takeIf { it.exists() }?.readText() ?: return null
                    if (kind == "startsession") withLedgerUnlocks(cached) else cached
                }
                // Softcore only: hardcore unlocks need a live connection
                "awardachievement" -> {
                    if (params["h"] == "1") return null
                    val id = params["a"]?.toIntOrNull() ?: return null
                    OfflineLedger.add(offlineDir!!, id, params["m"].orEmpty(), System.currentTimeMillis())
                    JSONObject().put("Success", true).put("AchievementID", id).put("Score", 0).put("SoftcoreScore", 0).toString()
                }
                "ping" -> JSONObject().put("Success", true).toString()
                else -> return null
            }
        if (!playingOffline) {
            playingOffline = true
            main.post { listener?.onEvent("offline", null, "Playing offline", 0) }
        }
        return answer.toByteArray()
    }

    /** The session as last seen online, plus anything earned offline since (so it isn't earned twice). */
    private fun withLedgerUnlocks(cached: String): String =
        runCatching {
            val o = JSONObject(cached)
            val unlocks = o.optJSONArray("Unlocks") ?: JSONArray().also { o.put("Unlocks", it) }
            val known = (0 until unlocks.length()).map { unlocks.optJSONObject(it)?.optInt("ID") ?: 0 }.toSet()
            OfflineLedger.pending(offlineDir!!).filter { it.id !in known }.forEach {
                unlocks.put(JSONObject().put("ID", it.id).put("When", it.time / 1000))
            }
            o.put("ServerNow", System.currentTimeMillis() / 1000)
            o.toString()
        }.getOrDefault(cached)
}
