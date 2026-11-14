package com.swordfish.libretrodroid

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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

        fun onUnlocked(title: String, description: String, points: Int, badgeUrl: String?)

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
    fun start(
        product: String,
        username: String,
        token: String,
        consoleId: Int,
        gamePath: String,
    ) {
        userAgent = product
        val clause = nativeStart(product, username, token, consoleId, gamePath)
        if (clause.isNotBlank()) userAgent = "$product $clause"
    }

    fun stop() {
        nativeStop()
    }

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
            when (kind) {
                "game_loaded" -> l.onGameLoaded(title ?: "", description ?: "", points, imageUrl)
                "unlocked" -> l.onUnlocked(title ?: "", description ?: "", points, imageUrl)
                "mastered" -> l.onMastered(title ?: "", imageUrl)
                "disconnected" -> l.onConnection(true)
                "reconnected" -> l.onConnection(false)
                "log", "login_ok", "game_info", "memory", "processing" -> Unit
                else -> l.onProblem(kind, description)
            }
        }
    }

    private const val RETRYABLE_CLIENT_ERROR = -2
}
