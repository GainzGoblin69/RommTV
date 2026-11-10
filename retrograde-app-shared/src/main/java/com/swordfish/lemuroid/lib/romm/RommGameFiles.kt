package com.swordfish.lemuroid.lib.romm

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.util.zip.ZipInputStream

/** Where downloaded games can live: the TV's own storage, or a USB drive plugged into it. */
data class GameStorage(val root: File, val isUsb: Boolean) {
    val label: String get() = if (isUsb) "USB drive" else "This TV"
}

/** A game downloaded to the TV or the USB drive. */
data class InstalledGameFiles(val romId: Int, val sizeBytes: Long, val isUsb: Boolean)

/**
 * Downloads a game from RomM the first time it is played, and keeps it as a cache on the TV or on a USB drive.
 * New downloads go where Settings says; games already on either one keep working.
 * When space runs low, the games played longest ago are removed first. Saves are kept elsewhere and never removed.
 */
class RommGameFiles(context: Context) {
    private val appContext = context.applicationContext
    private val session = RommSession(appContext)
    private val client = RommClient(session)

    /** The TV's own storage. */
    private val tvStorage: GameStorage
        get() = GameStorage(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "romm-games").apply { mkdirs() }, false)

    /** A USB drive (or SD card) plugged in and mounted as portable storage, if there is one. */
    val usbStorage: GameStorage?
        get() =
            appContext.getExternalFilesDirs(null)
                .drop(1)
                .filterNotNull()
                .firstOrNull { dir ->
                    runCatching {
                        Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                            Environment.isExternalStorageRemovable(dir)
                    }.getOrDefault(false)
                }
                ?.let { GameStorage(File(it, "romm-games").apply { mkdirs() }, true) }

    /** Every place games can be found right now. */
    fun storages(): List<GameStorage> = listOfNotNull(tvStorage, usbStorage)

    /** Where new downloads go: the USB drive when it's chosen in Settings and plugged in, otherwise the TV. */
    fun downloadTarget(): GameStorage = if (preferUsb(appContext)) usbStorage ?: tvStorage else tvStorage

    /** Called while a download runs (bytes done, bytes total), so the loading screen can show it. */
    var onProgress: ((Long, Long) -> Unit)? = null

    fun isDownloaded(romId: Int): Boolean = findReady(romId) != null

    /** The folder holding a finished download of this game, on whichever storage it's on. */
    private fun findReady(romId: Int): File? =
        storages().map { File(it.root, romId.toString()) }.firstOrNull { File(it, READY_MARKER).exists() }

    fun isOnUsb(romId: Int): Boolean = usbStorage?.let { File(File(it.root, romId.toString()), READY_MARKER).exists() } ?: false

    /** Returns the file to start the core with (the .cue / .m3u for disc games, else the game itself). */
    fun prepare(
        romId: Int,
        systemId: String,
    ): File {
        findReady(romId)?.let { found ->
            val marker = File(found, READY_MARKER)
            val cached = File(found, marker.readText().trim())
            if (cached.exists() && cached.length() > 0) {
                marker.setLastModified(System.currentTimeMillis())
                lastPrepared = romId to cached
                return cached
            }
        }

        // A new download: clear any half-finished copy, then download to the chosen storage
        storages().forEach { File(it.root, romId.toString()).deleteRecursively() }
        val target = downloadTarget()
        val dir = File(target.root, romId.toString())
        val marker = File(dir, READY_MARKER)
        dir.mkdirs()

        val rom = JSONObject(fetchRom(romId))
        val filesJson = rom.optJSONArray("files")
        // Only the game itself: skip covers, screenshots, manuals and other extras stored with it
        val files =
            (0 until (filesJson?.length() ?: 0)).map { filesJson!!.getJSONObject(it) }
                .filter { f ->
                    val category = if (f.isNull("category")) "game" else f.optString("category", "game")
                    category == "game" && File(f.getString("file_name")).extension.lowercase() !in EXTRAS
                }
                .map { f -> Triple(f.getInt("id"), f.getString("file_name"), f.optLong("file_size_bytes")) }
                .ifEmpty { throw RommException("This game has no game files on the server, only extras like artwork") }

        val total = files.sumOf { it.third }
        makeRoom(target, total * 2, keep = romId)

        var doneBefore = 0L
        files.forEach { (id, name, size) ->
            val dest = File(dir, name)
            client.download(client.romFileUrl(id, name), dest) { done, _ ->
                onProgress?.invoke(doneBefore + done, total)
            }
            checkDownload(dest, size)
            doneBefore += size
        }

        val keepZipped = systemId == SystemID.FBNEO.dbname || systemId == SystemID.MAME2003PLUS.dbname
        if (!keepZipped) extractArchives(dir)

        val primary =
            choosePrimary(dir, systemId)
                ?: throw RommException(
                    "None of this game's files can be played as ${systemName(systemId)}. Files: " +
                        (dir.listFiles()?.filter { it.name != READY_MARKER }?.joinToString(", ") { it.name } ?: "none"),
                )
        marker.writeText(primary.name)
        lastPrepared = romId to primary
        Timber.i("RomM game $romId ready: ${describe(primary)}")
        return primary
    }

    /** A download that came back empty, cut short, or as a web page instead of the game. */
    private fun checkDownload(
        file: File,
        expected: Long,
    ) {
        val got = file.length()
        if (got == 0L) {
            file.delete()
            throw RommException(
                "The server sent an empty file for ${file.name}. " +
                    "RomM serves downloads through its built-in web server (nginx); check that it's running.",
            )
        }
        if (expected > 0 && got != expected) {
            file.delete()
            throw RommException("The download of ${file.name} was incomplete ($got of $expected bytes). Try again.")
        }
        val head = file.inputStream().use { input -> ByteArray(64).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        val text = String(head, Charsets.ISO_8859_1).trimStart().lowercase()
        if (text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("{\"detail\"")) {
            file.delete()
            throw RommException("The server sent a web page instead of ${file.name}. Check the RomM server address.")
        }
    }

    private fun fetchRom(romId: Int): String {
        val url = Uri.parse(session.serverUrl).buildUpon().appendEncodedPath("api/roms/$romId").build().toString()
        val request =
            okhttp3.Request.Builder().url(url)
                .header("Authorization", "Bearer ${session.token}")
                .header("Accept", "application/json")
                .build()
        RommClient.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RommException("Couldn't read this game from RomM (${response.code})", response.code)
            return response.body!!.string()
        }
    }

    /** Most cores can't read compressed games, so .zip and .7z files are unpacked next to them. */
    private fun extractArchives(dir: File) {
        dir.listFiles()?.forEach { archive ->
            when (archive.extension.lowercase()) {
                "zip" ->
                    runCatching {
                        ZipInputStream(archive.inputStream().buffered()).use { stream ->
                            while (true) {
                                val entry = stream.nextEntry ?: break
                                if (entry.isDirectory) continue
                                File(dir, File(entry.name).name).outputStream().use { stream.copyTo(it) }
                            }
                        }
                        archive.delete()
                    }.onFailure { throw RommException("Couldn't unpack ${archive.name}: ${it.message}") }
                "7z" ->
                    runCatching {
                        SevenZFile.builder().setFile(archive).get().use { sz ->
                            val buffer = ByteArray(256 * 1024)
                            var entry = sz.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    File(dir, File(entry.name).name).outputStream().use { out ->
                                        while (true) {
                                            val n = sz.read(buffer)
                                            if (n < 0) break
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                }
                                entry = sz.nextEntry
                            }
                        }
                        archive.delete()
                    }.onFailure { throw RommException("Couldn't unpack ${archive.name}: ${it.message}") }
            }
        }
    }

    /** The file to start: a disc list or cue sheet first, else the largest file of a type this system plays. */
    private fun choosePrimary(
        dir: File,
        systemId: String,
    ): File? {
        val playable = runCatching { GameSystem.findById(systemId).supportedExtensions }.getOrDefault(emptyList()).map { it.lowercase() }
        val files =
            dir.listFiles()?.filter {
                it.isFile && !it.name.endsWith(".part") && it.name != READY_MARKER &&
                    it.extension.lowercase() !in EXTRAS
            } ?: return null
        for (ext in PRIMARY_ORDER) {
            if (playable.isNotEmpty() && ext !in playable) continue
            files.firstOrNull { it.extension.equals(ext, true) }?.let { return it }
        }
        val candidates = if (playable.isEmpty()) files else files.filter { it.extension.lowercase() in playable }
        return candidates.maxByOrNull { it.length() }
    }

    private fun systemName(systemId: String): String =
        runCatching { appContext.getString(GameSystem.findById(systemId).titleResId) }.getOrDefault(systemId)

    /** Frees space on [storage] by removing the games played longest ago, keeping [needed] bytes plus a margin free. */
    private fun makeRoom(
        storage: GameStorage,
        needed: Long,
        keep: Int,
    ) {
        val margin = 500L * 1024 * 1024
        fun free(): Long = runCatching { StatFs(storage.root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free() > needed + margin) return
        val oldestFirst =
            storage.root.listFiles()?.filter { it.isDirectory && it.name != keep.toString() }
                ?.sortedBy { File(it, READY_MARKER).lastModified() } ?: return
        for (game in oldestFirst) {
            if (free() > needed + margin) break
            Timber.i("Removing ${game.name} to make room")
            game.deleteRecursively()
        }
        if (free() < needed) throw RommException("Not enough space on the ${if (storage.isUsb) "USB drive" else "TV"} for this game")
    }

    /** Deletes the game from the TV and the USB drive. */
    fun remove(romId: Int) {
        storages().forEach { File(it.root, romId.toString()).deleteRecursively() }
    }

    /** Games downloaded to the TV and the USB drive. */
    fun installed(): List<InstalledGameFiles> =
        storages().flatMap { storage ->
            storage.root.listFiles()
                ?.filter { it.isDirectory && File(it, READY_MARKER).exists() }
                ?.mapNotNull { dir ->
                    dir.name.toIntOrNull()?.let {
                        InstalledGameFiles(it, dir.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }, storage.isUsb)
                    }
                } ?: emptyList()
        }.distinctBy { it.romId }

    /** Free and total bytes on one storage. */
    fun space(storage: GameStorage): Pair<Long, Long> =
        runCatching { StatFs(storage.root.path).let { it.availableBytes to it.totalBytes } }.getOrDefault(0L to 0L)

    /**
     * Moves a downloaded game from the TV to the USB drive: copies it, checks the copy, then deletes the original.
     * Returns false (and leaves the game where it was) if it doesn't fit.
     */
    fun moveToUsb(romId: Int): Boolean {
        val usb = usbStorage ?: return false
        return move(romId, tvStorage, usb)
    }

    /** Moves a game from the USB drive back to the TV. */
    fun moveToTv(romId: Int): Boolean {
        val usb = usbStorage ?: return false
        return move(romId, usb, tvStorage)
    }

    private fun move(
        romId: Int,
        from: GameStorage,
        to: GameStorage,
    ): Boolean {
        val source = File(from.root, romId.toString())
        if (!File(source, READY_MARKER).exists()) return false
        val size = source.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (space(to).first < size + 50L * 1024 * 1024) return false
        val dest = File(to.root, romId.toString())
        val temp = File(to.root, "$romId.moving")
        temp.deleteRecursively()
        try {
            source.copyRecursively(temp, overwrite = true)
            val copied = temp.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (copied != size) error("copy was incomplete ($copied of $size bytes)")
            dest.deleteRecursively()
            if (!temp.renameTo(dest)) error("couldn't finish the move")
        } catch (e: Throwable) {
            temp.deleteRecursively()
            Timber.e(e, "Couldn't move game $romId")
            return false
        }
        source.deleteRecursively()
        return true
    }

    companion object {
        /** The last game handed to the emulator in this process, so a load error can say which file and clear it. */
        @Volatile
        var lastPrepared: Pair<Int, File>? = null

        fun describe(file: File): String {
            val head =
                runCatching {
                    file.inputStream().use { input -> ByteArray(8).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
                }.getOrDefault(ByteArray(0))
            val hex = head.joinToString(" ") { "%02x".format(it) }
            return "${file.name}, ${file.length()} bytes, starts $hex"
        }

        private const val READY_MARKER = ".ready"
        private val PRIMARY_ORDER = listOf("m3u", "cue", "chd", "pbp", "gdi", "ccd", "iso", "cso")

        /** Files stored alongside a game that are never the game itself. */
        private val EXTRAS =
            setOf(
                "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg",
                "txt", "nfo", "diz", "md", "pdf", "html", "htm", "xml", "json", "sfv", "md5", "sha1",
                "mp3", "ogg", "flac", "wav", "mp4", "mkv",
            )

        const val SCHEME = "romm"

        private const val STORAGE_PREFS = "romm_storage"
        private const val KEY_PREFER_USB = "prefer_usb"

        /** Whether new downloads should go to the USB drive (set in Settings). */
        fun preferUsb(context: Context): Boolean =
            context.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREFER_USB, false)

        fun setPreferUsb(
            context: Context,
            value: Boolean,
        ) {
            context.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PREFER_USB, value).commit()
        }

        fun gameUri(
            romId: Int,
            platformId: Int,
        ): Uri = Uri.parse("$SCHEME://rom/$romId?platform=$platformId")

        fun romId(uri: Uri): Int? = uri.pathSegments.firstOrNull()?.toIntOrNull()

        fun platformId(uri: Uri): Int? = uri.getQueryParameter("platform")?.toIntOrNull()
    }
}
