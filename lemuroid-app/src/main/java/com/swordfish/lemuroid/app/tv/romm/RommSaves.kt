package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommGameFiles
import com.swordfish.lemuroid.lib.romm.RommSave
import com.swordfish.lemuroid.lib.romm.RommSession
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Moves in-game saves (the game's own battery save, .srm) between this TV and RomM:
 * a save picked on the game page is downloaded before the game starts, and the TV's save is uploaded
 * to its own "RomM TV" slot after playing, next to the web player's saves.
 */
object RommSaves {
    private const val PREFS = "romm_saves"

    fun romId(game: Game): Int? =
        Uri.parse(game.fileUri).takeIf { it.scheme == RommGameFiles.SCHEME }?.let { RommGameFiles.romId(it) }

    /** Where the emulator keeps this game's save (the same name Lemuroid's SavesManager uses). */
    fun localSave(
        context: Context,
        game: Game,
    ): File = File(DirectoriesManager(context.applicationContext).getSavesDirectory(), "${game.fileName.substringBeforeLast(".")}.srm")

    /** Puts a save from RomM in place of the TV's save. The TV's previous save is kept beside it as .bak. */
    fun useServerSave(
        context: Context,
        game: Game,
        save: RommSave,
    ) {
        val client = RommClient(RommSession(context))
        val local = localSave(context, game)
        val incoming = File(local.parentFile, local.name + ".incoming")
        client.downloadSave(save.id, incoming)
        if (incoming.length() == 0L) {
            incoming.delete()
            error("The save came back empty")
        }
        if (local.exists()) local.copyTo(File(local.parentFile, local.name + ".bak"), overwrite = true)
        incoming.copyTo(local, overwrite = true)
        incoming.delete()
        local.setLastModified(System.currentTimeMillis())
        remember(context, game, sha1(local))
    }

    /** Uploads the TV's save if it changed since the last upload or download. Returns true when something was sent. */
    fun uploadAfterPlay(
        context: Context,
        game: Game,
    ): Boolean {
        val romId = romId(game) ?: return false
        val local = localSave(context, game)
        if (!local.exists() || local.length() == 0L) return false
        val hash = sha1(local)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(romId.toString(), null) == hash) return false
        RommClient(RommSession(context)).uploadSave(romId, local, local.name)
        remember(context, game, hash)
        Timber.i("Uploaded save for RomM game $romId")
        return true
    }

    private fun remember(
        context: Context,
        game: Game,
        hash: String,
    ) {
        val romId = romId(game) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(romId.toString(), hash).apply()
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
