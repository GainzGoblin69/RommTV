package com.swordfish.libretrodroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Softcore unlocks earned without a connection, in the order they happened, until they're sent to
 * RetroAchievements. Kept in one small file that games and the app (separate processes) both use, under a
 * file lock.
 */
object OfflineLedger {
    data class Entry(val id: Int, val hash: String, val time: Long)

    private fun file(dir: File) = File(dir.apply { mkdirs() }, "offline-unlocks.json")

    @Synchronized
    private fun <T> locked(
        dir: File,
        block: (MutableList<Entry>) -> T,
    ): T {
        val f = file(dir)
        val raf = RandomAccessFile(File(dir, "offline-unlocks.lock"), "rw")
        try {
            val lock = raf.channel.lock()
            try {
                val list = read(f)
                val before = list.toList()
                val result = block(list)
                if (list != before) write(f, list)
                return result
            } finally {
                lock.release()
            }
        } finally {
            raf.close()
        }
    }

    private fun read(f: File): MutableList<Entry> =
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getInt("id"), it.optString("hash"), it.getLong("time")) }
        }.getOrDefault(emptyList()).toMutableList()

    private fun write(
        f: File,
        list: List<Entry>,
    ) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("hash", it.hash).put("time", it.time)) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    fun add(
        dir: File,
        id: Int,
        hash: String,
        time: Long,
    ) = locked(dir) { list -> if (list.none { it.id == id }) list += Entry(id, hash, time) }

    fun pending(dir: File): List<Entry> = locked(dir) { it.toList() }

    fun remove(
        dir: File,
        ids: Set<Int>,
    ) = locked(dir) { list -> list.removeAll { it.id in ids } }
}
