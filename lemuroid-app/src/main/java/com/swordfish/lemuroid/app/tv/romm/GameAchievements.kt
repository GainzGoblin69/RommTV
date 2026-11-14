package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.libretrodroid.RetroAchievements
import timber.log.Timber
import java.io.File

/** Starts RetroAchievements for a game once it's running (in the game's process). */
object GameAchievements {
    /** RetroAchievements' number for each console. */
    fun consoleId(system: SystemID): Int? =
        when (system) {
            SystemID.GENESIS -> 1
            SystemID.N64 -> 2
            SystemID.SNES -> 3
            SystemID.GB -> 4
            SystemID.GBA -> 5
            SystemID.GBC -> 6
            SystemID.NES -> 7
            SystemID.PC_ENGINE -> 8
            SystemID.SEGACD -> 9
            SystemID.SMS -> 11
            SystemID.PSX -> 12
            SystemID.LYNX -> 13
            SystemID.NGP, SystemID.NGC -> 14
            SystemID.GG -> 15
            SystemID.NDS -> 18
            SystemID.ATARI2600 -> 25
            SystemID.FBNEO, SystemID.MAME2003PLUS -> 27
            SystemID.PSP -> 41
            SystemID.ATARI7800 -> 51
            SystemID.WS, SystemID.WSC -> 53
            else -> null
        }

    fun start(
        context: Context,
        gameData: GameLoader.GameData,
    ) {
        val game = gameData.game
        fun skip(reason: String) = AchievementSession.skipped(context, game.fileUri, game.title, reason)

        val account = RetroAchievementsAccount.load(context)
            ?: return skip("Not signed in to RetroAchievements on this ${com.swordfish.lemuroid.lib.romm.Device.word}. Settings → RetroAchievements.")
        if (!account.enabled) return skip("RetroAchievements is switched off in Settings.")
        val system = SystemID.values().firstOrNull { it.dbname == game.systemId }
        val console = system?.let { consoleId(it) } ?: return skip("RetroAchievements doesn't cover this console.")
        val file: File = (gameData.gameFiles as? RomFiles.Standard)?.files?.firstOrNull()
            ?: return skip("The game isn't a plain file on this device, so it can't be identified.")

        AchievementSession.begin(context, game.fileUri, game.title, "Signing in as ${account.username}…")
        AchievementSession.log(context, "Game file: ${file.name} (${file.length()} bytes), console #$console")
        runCatching {
            RetroAchievements.start(RetroAchievementsAccount.product, account.username, account.token, console, file.absolutePath)
        }.onFailure {
            Timber.e(it, "Couldn't start RetroAchievements")
            AchievementSession.problem(context, "Couldn't start achievements: ${it.message}")
        }
    }
}
