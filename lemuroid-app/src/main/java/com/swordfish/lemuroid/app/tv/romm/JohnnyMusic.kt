package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * "Johnny Music": a hidden extra. Once unlocked (hold Select on the Menu music tile and enter the folder's
 * password), the game menu gets a Johnny Music switch that mutes the game and shuffles a second, much bigger
 * music folder instead. Nothing is shown on screen while it plays, and nothing is downloaded: songs stream.
 *
 * The folder can be huge, so it's never listed in full. Each song is found by starting at the top and stepping
 * into a random folder until a song turns up; folder listings are remembered while the game is open.
 */
object JohnnyMusic {
    data class Config(val url: String, val password: String)

    /** Where the second folder is expected (the same Copyparty as the menu music, under "johnny"). */
    const val DEFAULT_URL = "https://music.alice99.box.ca/johnny/"

    private val AUDIO = MenuMusic.AUDIO
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listings = ConcurrentHashMap<String, Pair<List<String>, List<String>>>()

    private var player: MediaPlayer? = null

    @Volatile
    private var on = false
    private var paused = false
    private var failures = 0
    private var generation = 0

    private fun file(context: Context) = File(context.applicationContext.filesDir, "johnny-music.json")

    /** The saved folder and password, or null while the extra is locked. */
    fun config(context: Context): Config? =
        runCatching {
            val o = JSONObject(file(context).readText())
            Config(o.getString("url"), o.optString("password")).takeIf { it.url.isNotBlank() }
        }.getOrNull()

    fun isUnlocked(context: Context) = config(context) != null

    fun lock(context: Context) {
        file(context).delete()
    }

    /**
     * Checks the folder with this password and, if it answers, unlocks the extra (blocking; run off the main
     * thread). Throws with a readable reason if the folder can't be read.
     */
    fun unlock(
        context: Context,
        url: String,
        password: String,
    ) {
        val config = Config(MenuMusic.normalize(url), password)
        val (dirs, files) = list(config, config.url)
        check(dirs.isNotEmpty() || files.isNotEmpty()) { "that folder is empty" }
        file(context).writeText(JSONObject().put("url", config.url).put("password", config.password).toString())
    }

    val isOn get() = on

    fun setOn(
        context: Context,
        value: Boolean,
    ) {
        if (value == on) return
        on = value
        generation++
        failures = 0
        if (value) {
            paused = false
            playNext(context.applicationContext, generation)
        } else {
            release()
        }
    }

    /** The app left the screen: hold the music until it's back. */
    fun pause() {
        if (!on) return
        paused = true
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume(context: Context) {
        if (!on || !paused) return
        paused = false
        val p = player
        if (p != null) runCatching { p.start() } else playNext(context.applicationContext, generation)
    }

    /** The game closed. */
    fun stop() {
        on = false
        generation++
        release()
        listings.clear()
    }

    private fun release() {
        val p = player
        player = null
        runCatching { p?.reset() }
        runCatching { p?.release() }
    }

    private fun playNext(
        context: Context,
        gen: Int,
    ) {
        val config = config(context) ?: return
        worker.execute {
            val url = runCatching { randomSong(config) }.onFailure { Timber.w(it, "Johnny Music: couldn't pick a song") }.getOrNull()
            main.post {
                if (gen != generation || !on) return@post
                if (url == null) {
                    retryLater(context, gen)
                    return@post
                }
                release()
                runCatching {
                    val mp = MediaPlayer()
                    player = mp
                    mp.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    )
                    mp.setDataSource(
                        context,
                        Uri.parse(url),
                        MenuMusic.authHeaders(MenuMusic.Settings(config.url, config.password, true, 0, 0)),
                    )
                    mp.setOnPreparedListener {
                        if (gen != generation || !on) return@setOnPreparedListener
                        failures = 0
                        if (!paused) it.start()
                    }
                    mp.setOnCompletionListener { if (gen == generation && on) playNext(context, gen) }
                    mp.setOnErrorListener { _, _, _ ->
                        if (gen == generation && on) retryLater(context, gen)
                        true
                    }
                    mp.prepareAsync()
                }.onFailure { retryLater(context, gen) }
            }
        }
    }

    /** A song that won't play is skipped; if several in a row fail (no connection), it waits before trying again. */
    private fun retryLater(
        context: Context,
        gen: Int,
    ) {
        release()
        failures++
        main.postDelayed({ if (gen == generation && on) playNext(context, gen) }, if (failures < 4) 500L else 20_000L)
    }

    /** Steps from the top into random folders until it lands on a song. Null if none turned up. */
    private fun randomSong(config: Config): String? {
        repeat(12) {
            var dir = config.url
            for (depth in 0 until 12) {
                val (dirs, files) = listings[dir] ?: list(config, dir).also { listings[dir] = it }
                val total = dirs.size + files.size
                if (total == 0) break
                val pick = (0 until total).random()
                if (pick >= dirs.size) return dir + files[pick - dirs.size]
                dir += dirs[pick]
            }
        }
        return null
    }

    /** One folder's sub-folders and songs, as links relative to it. */
    private fun list(
        config: Config,
        dirUrl: String,
    ): Pair<List<String>, List<String>> {
        val connection = URL("$dirUrl?ls").openConnection() as HttpURLConnection
        val text =
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                MenuMusic.authHeaders(MenuMusic.Settings(config.url, config.password, true, 0, 0))
                    .forEach { (k, v) -> connection.setRequestProperty(k, v) }
                val code = connection.responseCode
                if (code == 401 || code == 403) throw IllegalStateException("the password was refused")
                if (code == 404) throw IllegalStateException("that folder wasn't found on the server")
                if (code >= 400) throw IllegalStateException("the server answered HTTP $code")
                connection.inputStream.use { it.reader().readText() }
            } finally {
                connection.disconnect()
            }
        val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("the password was refused") }

        fun hrefs(name: String) =
            json.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("href") } }.orEmpty()
        val dirs =
            hrefs("dirs").map { it.substringBefore('?') }.filter { it.isNotBlank() && !it.startsWith(".") }
                .map { if (it.endsWith("/")) it else "$it/" }
        val files = hrefs("files").filter { it.substringBefore('?').substringAfterLast('.').lowercase() in AUDIO }
        return dirs to files
    }
}
