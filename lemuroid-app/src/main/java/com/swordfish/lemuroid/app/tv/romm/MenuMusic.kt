package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Background music for the menus: shuffles every song in one music folder on your server (shared with
 * Copyparty, read-only, password protected). Albums are folders inside it; folders inside albums (Disc 1…) work too.
 *
 * Plays quietly while the app is on screen, fades out when a game starts or you leave the app, fades back in
 * when you return. Runs in the app's main process only (games run in their own).
 */
object MenuMusic {
    data class Track(val url: String, val title: String, val album: String, val coverUrl: String?)

    data class NowPlaying(val title: String, val album: String, val coverUrl: String?)

    data class Settings(val url: String, val password: String, val enabled: Boolean, val volume: Int, val idleMinutes: Int)

    private const val PREFS = "romm_music"
    val AUDIO = setOf("mp3", "ogg", "oga", "opus", "m4a", "aac", "flac", "wav")
    private val IMAGES = listOf("cover", "folder", "front", "album", "art")
    private const val MAX_DEPTH = 3

    /** Volume levels for the Settings tile (menu music sits well under game and UI sound). */
    val VOLUMES = listOf(0.12f, 0.22f, 0.35f, 0.5f)
    val VOLUME_NAMES = listOf("Quiet", "Low", "Medium", "Loud")

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private var player: MediaPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var queue = ArrayDeque<Track>()
    private var started = 0
    private var foreground = false
    private var fadeJob: Job? = null
    private var loadJob: Job? = null
    private var pauseJob: Job? = null
    private var failures = 0
    private var currentVolume = 0f

    // ---- settings

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            url = p.getString("url", "") ?: "",
            password = p.getString("password", "") ?: "",
            enabled = p.getBoolean("enabled", true),
            volume = p.getInt("volume", 1),
            idleMinutes = p.getInt("idle", 2),
        )
    }

    fun save(
        context: Context,
        settings: Settings,
    ) {
        prefs(context).edit()
            .putString("url", normalize(settings.url))
            .putString("password", settings.password)
            .putBoolean("enabled", settings.enabled)
            .putInt("volume", settings.volume)
            .putInt("idle", settings.idleMinutes)
            .apply()
        if (::app.isInitialized) restart()
    }

    fun normalize(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    fun isConfigured(context: Context) = settings(context).url.isNotBlank()

    // ---- lifecycle: plays while one of the app's screens is showing

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application.applicationContext
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    started++
                    pauseJob?.cancel()
                    if (!foreground) {
                        foreground = true
                        resume()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    if (started == 0) {
                        // A short wait, so moving between the app's own screens doesn't stop the music
                        pauseJob?.cancel()
                        pauseJob =
                            scope.launch {
                                delay(700)
                                foreground = false
                                pause()
                            }
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun targetVolume() = VOLUMES[settings(app).volume.coerceIn(0, VOLUMES.lastIndex)]

    private fun restart() {
        scope.launch {
            stopPlayer()
            tracks = emptyList()
            queue.clear()
            _nowPlaying.value = null
            if (foreground) resume()
        }
    }

    private fun resume() {
        val s = settings(app)
        if (!s.enabled || s.url.isBlank()) return
        val p = player
        if (p != null) {
            runCatching { if (!p.isPlaying) p.start() }
            fadeTo(targetVolume(), 1500)
            return
        }
        if (loadJob?.isActive == true) return
        loadJob =
            scope.launch {
                if (tracks.isEmpty()) {
                    // Lets the startup splash and its chime finish first
                    delay(3000)
                    tracks = withContext(Dispatchers.IO) { runCatching { scan(s) }.onFailure { Timber.e(it, "Menu music: couldn't list") }.getOrDefault(emptyList()) }
                }
                if (tracks.isNotEmpty() && foreground) playNext()
            }
    }

    private fun pause() {
        fadeTo(0f, 900) { runCatching { player?.pause() } }
    }

    private fun fadeTo(
        target: Float,
        millis: Long,
        then: () -> Unit = {},
    ) {
        fadeJob?.cancel()
        fadeJob =
            scope.launch {
                val from = currentVolume
                val steps = 20
                for (i in 1..steps) {
                    currentVolume = from + (target - from) * i / steps
                    runCatching { player?.setVolume(currentVolume, currentVolume) }
                    delay(millis / steps)
                }
                then()
            }
    }

    private fun stopPlayer() {
        fadeJob?.cancel()
        player?.let { runCatching { it.release() } }
        player = null
        currentVolume = 0f
    }

    /** Skips to another song (from the "now playing" line). */
    fun skip() {
        if (!::app.isInitialized) return
        scope.launch {
            fadeTo(0f, 500) {
                stopPlayer()
                playNext()
            }
        }
    }

    private fun nextTrack(): Track? {
        if (tracks.isEmpty()) return null
        if (queue.isEmpty()) {
            // A full shuffle: every song plays once before any repeats
            val last = _nowPlaying.value?.title
            queue = ArrayDeque(tracks.shuffled().let { if (it.size > 1 && it.first().title == last) it.drop(1) + it.first() else it })
        }
        return queue.removeFirstOrNull()
    }

    private fun playNext() {
        if (!foreground) return
        val s = settings(app)
        if (!s.enabled) return
        val track = nextTrack() ?: return
        stopPlayer()
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mp.setDataSource(app, Uri.parse(track.url), authHeaders(s))
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                failures = 0
                _nowPlaying.value = NowPlaying(track.title, track.album, track.coverUrl)
                if (foreground) {
                    it.start()
                    fadeTo(targetVolume(), 2000)
                }
            }
            mp.setOnCompletionListener { if (player === mp) playNext() }
            mp.setOnErrorListener { _, what, extra ->
                Timber.w("Menu music: couldn't play ${track.url} ($what/$extra)")
                if (player === mp) {
                    failures++
                    if (failures < 5) scope.launch { playNext() } else stopPlayer()
                }
                true
            }
            mp.prepareAsync()
        }.onFailure {
            Timber.e(it, "Menu music: couldn't start ${track.url}")
            failures++
            if (failures < 5) scope.launch { playNext() }
        }
    }

    // ---- the music folder

    fun authHeaders(settings: Settings): Map<String, String> =
        if (settings.password.isBlank()) {
            emptyMap()
        } else {
            mapOf(
                "PW" to settings.password,
                "Authorization" to "Basic " + Base64.encodeToString("romm:${settings.password}".toByteArray(), Base64.NO_WRAP),
            )
        }

    /** Lists every song in the folder (blocking). Throws with a readable message if the folder can't be read. */
    fun scan(settings: Settings): List<Track> {
        val root = normalize(settings.url)
        val found = mutableListOf<Track>()
        walk(settings, root, rootName(root), 0, found)
        return found
    }

    private fun rootName(url: String) = URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8")

    private fun walk(
        settings: Settings,
        dirUrl: String,
        album: String,
        depth: Int,
        out: MutableList<Track>,
    ) {
        val json = JSONObject(get(settings, "$dirUrl?ls"))
        val files = json.optJSONArray("files")
        val dirs = json.optJSONArray("dirs")
        val names = (0 until (files?.length() ?: 0)).map { files!!.getJSONObject(it) }
        val cover =
            names.firstOrNull { f ->
                val n = f.optString("name").lowercase()
                n.substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp") && IMAGES.any { n.startsWith(it) }
            } ?: names.firstOrNull { it.optString("name").lowercase().substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp") }
        val coverUrl = cover?.let { dirUrl + it.optString("href") }
        names.filter { it.optString("name").substringAfterLast('.').lowercase() in AUDIO }.forEach { f ->
            out += Track(dirUrl + f.optString("href"), titleOf(f.optString("name")), album, coverUrl)
        }
        if (depth >= MAX_DEPTH) return
        for (i in 0 until (dirs?.length() ?: 0)) {
            val d = dirs!!.getJSONObject(i)
            val name = d.optString("name").ifBlank { URLDecoder.decode(d.optString("href").trimEnd('/'), "UTF-8") }
            if (name.startsWith(".")) continue
            val href = d.optString("href").let { if (it.endsWith("/")) it else "$it/" }
            // Folders inside an album (Disc 1, CD2…) keep the album's name
            val childAlbum = if (depth == 0) name else album
            runCatching { walk(settings, dirUrl + href, childAlbum, depth + 1, out) }
                .onFailure { Timber.w(it, "Menu music: skipped folder $name") }
        }
    }

    /** "01 - Main Theme.mp3" → "Main Theme". */
    fun titleOf(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace(Regex("^\\s*(\\d{1,3}[-.]?)?\\d{1,3}\\s*[-._)]*\\s*"), "")
            .replace('_', ' ')
            .trim()
            .ifBlank { fileName.substringBeforeLast('.') }

    private fun get(
        settings: Settings,
        url: String,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            authHeaders(settings).forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("the password was refused")
            if (code == 404) throw IllegalStateException("that folder wasn't found")
            if (code >= 400) throw IllegalStateException("the server answered HTTP $code")
            return connection.inputStream.use { it.reader().readText() }
        } finally {
            connection.disconnect()
        }
    }
}
