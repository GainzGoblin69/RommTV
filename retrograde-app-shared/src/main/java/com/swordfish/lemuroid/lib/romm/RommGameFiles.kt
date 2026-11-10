package com.swordfish.lemuroid.lib.romm

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.swordfish.lemuroid.lib.library.SystemID
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Downloads a game from RomM the first time it is played, and keeps it on the TV as a cache.
 * When space runs low, the games played longest ago are removed first. Saves are kept elsewhere and never removed.
 */
class RommGameFiles(context: Context) {
    private val appContext = context.applicationContext
    private val session = RommSession(appContext)
    private val client = RommClient(session)

    private val gamesRoot: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "romm-games").apply { mkdirs() }

    /** Called while a download runs (bytes done, bytes total), so the loading screen can show it. */
    var onProgress: ((Long, Long) -> Unit)? = null

    fun isDownloaded(romId: Int): Boolean = File(gameDir(romId), READY_MARKER).exists()

    private fun gameDir(romId: Int) = File(gamesRoot, romId.toString())

    /** Returns the file to start the core with (the .cue / .m3u for disc games, else the game itself). */
    fun prepare(
        romId: Int,
        systemId: String,
    ): File {
        val dir = gameDir(romId)
        val marker = File(dir, READY_MARKER)
        if (marker.exists()) {
            marker.setLastModified(System.currentTimeMillis())
            return File(dir, marker.readText().trim())
        }

        dir.deleteRecursively()
        dir.mkdirs()

        val rom = JSONObject(fetchRom(romId))
        val filesJson = rom.optJSONArray("files")
        val files =
            (0 until (filesJson?.length() ?: 0)).map { i ->
                val f = filesJson!!.getJSONObject(i)
                Triple(f.getInt("id"), f.getString("file_name"), f.optLong("file_size_bytes"))
            }.ifEmpty { throw RommException("This game has no files on the server") }

        val total = files.sumOf { it.third }
        makeRoom(total * 2)

        var doneBefore = 0L
        files.forEach { (id, name, size) ->
            val dest = File(dir, name)
            client.download(client.romFileUrl(id, name), dest) { done, _ ->
                onProgress?.invoke(doneBefore + done, total)
            }
            doneBefore += size
        }

        val keepZipped = systemId == SystemID.FBNEO.dbname || systemId == SystemID.MAME2003PLUS.dbname
        if (!keepZipped) unzipAll(dir)

        val primary = choosePrimary(dir) ?: throw RommException("Couldn't find a playable file in this game")
        marker.writeText(primary.name)
        return primary
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

    private fun unzipAll(dir: File) {
        dir.listFiles()?.filter { it.extension.equals("zip", true) }?.forEach { zip ->
            runCatching {
                ZipInputStream(zip.inputStream().buffered()).use { stream ->
                    while (true) {
                        val entry = stream.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val out = File(dir, File(entry.name).name)
                        out.outputStream().use { stream.copyTo(it) }
                    }
                }
                zip.delete()
            }.onFailure { Timber.e(it, "Couldn't unzip ${zip.name}") }
        }
    }

    private fun choosePrimary(dir: File): File? {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.name != READY_MARKER } ?: return null
        for (ext in PRIMARY_ORDER) {
            files.firstOrNull { it.extension.equals(ext, true) }?.let { return it }
        }
        return files.maxByOrNull { it.length() }
    }

    /** Frees space by removing the games played longest ago, keeping at least [needed] bytes plus a margin free. */
    private fun makeRoom(needed: Long) {
        val margin = 500L * 1024 * 1024
        fun free(): Long = runCatching { StatFs(gamesRoot.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free() > needed + margin) return
        val oldestFirst =
            gamesRoot.listFiles()?.filter { it.isDirectory }
                ?.sortedBy { File(it, READY_MARKER).lastModified() } ?: return
        for (game in oldestFirst) {
            if (free() > needed + margin) break
            Timber.i("Removing ${game.name} to make room")
            game.deleteRecursively()
        }
        if (free() < needed) throw RommException("Not enough space on the TV for this game")
    }

    fun remove(romId: Int) {
        gameDir(romId).deleteRecursively()
    }

    companion object {
        private const val READY_MARKER = ".ready"
        private val PRIMARY_ORDER = listOf("m3u", "cue", "chd", "pbp", "gdi", "ccd", "iso", "cso")

        const val SCHEME = "romm"

        fun gameUri(
            romId: Int,
            platformId: Int,
        ): Uri = Uri.parse("$SCHEME://rom/$romId?platform=$platformId")

        fun romId(uri: Uri): Int? = uri.pathSegments.firstOrNull()?.toIntOrNull()

        fun platformId(uri: Uri): Int? = uri.getQueryParameter("platform")?.toIntOrNull()
    }
}
