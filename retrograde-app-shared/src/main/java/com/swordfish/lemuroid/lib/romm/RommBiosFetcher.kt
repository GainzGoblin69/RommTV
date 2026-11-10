package com.swordfish.lemuroid.lib.romm

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import timber.log.Timber
import java.io.File

/**
 * BIOS files are fetched from the game's platform in RomM's firmware list, the first time a game needs them.
 * They're matched by checksum first, then by file name, and saved under the name the emulator core expects.
 */
class RommBiosFetcher(
    context: Context,
    private val directoriesManager: DirectoriesManager,
) {
    private val client = RommClient(RommSession(context))

    /** Tries to fetch every missing file. Returns the ones still missing afterwards. */
    fun fetchMissing(
        game: Game,
        missing: List<String>,
    ): List<String> {
        if (missing.isEmpty()) return missing
        val platformId = RommGameFiles.platformId(Uri.parse(game.fileUri)) ?: return missing

        val firmware =
            runCatching { client.firmware(platformId) }
                .onFailure { Timber.e(it, "Couldn't list firmware for platform $platformId") }
                .getOrNull() ?: return missing

        missing.forEach { name ->
            val bios = BiosManager.SUPPORTED_BIOS.firstOrNull { it.libretroFileName == name }
            val match =
                firmware.firstOrNull { f -> bios != null && f.md5.equals(bios.md5, true) }
                    ?: firmware.firstOrNull { f -> bios?.externalCRC32 != null && f.crc.equals(bios.externalCRC32, true) }
                    ?: firmware.firstOrNull { f -> f.fileName.equals(name, true) }
                    ?: firmware.firstOrNull { f -> bios?.externalName != null && f.fileName.equals(bios.externalName, true) }
            if (match != null) {
                runCatching {
                    client.download(client.firmwareUrl(match), File(directoriesManager.getSystemDirectory(), name))
                    Timber.i("Fetched BIOS $name from RomM (${match.fileName})")
                }.onFailure { Timber.e(it, "Couldn't fetch BIOS $name") }
            }
        }

        return missing.filter { !File(directoriesManager.getSystemDirectory(), it).exists() }
    }
}
