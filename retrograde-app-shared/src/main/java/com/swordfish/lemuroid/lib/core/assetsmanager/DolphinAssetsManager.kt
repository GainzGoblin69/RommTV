package com.swordfish.lemuroid.lib.core.assetsmanager

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Dolphin (GameCube/Wii) needs its "Sys" folder (game settings, fonts, shaders) in the BIOS/system folder as
 * dolphin-emu/Sys. It's downloaded once from libretro's build server, like RetroArch's "core system files".
 */
class DolphinAssetsManager : CoreID.AssetsManager {
    override suspend fun clearAssets(directoriesManager: DirectoriesManager) {
        withContext(Dispatchers.IO) { File(directoriesManager.getSystemDirectory(), FOLDER).deleteRecursively() }
    }

    override suspend fun retrieveAssetsIfNeeded(
        coreUpdaterApi: CoreUpdater.CoreManagerApi,
        directoriesManager: DirectoriesManager,
        sharedPreferences: SharedPreferences,
    ) {
        val system = withContext(Dispatchers.IO) { directoriesManager.getSystemDirectory() }
        val sys = File(system, "$FOLDER/Sys")
        if (sharedPreferences.getString(VERSION_KEY, null) == VERSION && sys.isDirectory) return
        try {
            val response = coreUpdaterApi.downloadZip(URL)
            if (!response.isSuccessful) throw Exception("HTTP ${response.code()}")
            withContext(Dispatchers.IO) {
                response.body()?.use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // Only what belongs in the system folder, and nothing that climbs out of it
                        if (entry.name.contains("..")) continue
                        val dest = File(system, entry.name)
                        if (entry.isDirectory) {
                            dest.mkdirs()
                        } else {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            if (sys.isDirectory) sharedPreferences.edit().putString(VERSION_KEY, VERSION).apply()
        } catch (e: Throwable) {
            // Dolphin still starts without it; some games may look or behave wrong
            Timber.w(e, "Couldn't get Dolphin's system files")
        }
    }

    companion object {
        private const val FOLDER = "dolphin-emu"
        private const val URL = "https://buildbot.libretro.com/assets/system/Dolphin.zip"
        private const val VERSION = "1"
        private const val VERSION_KEY = "dolphin_sys_version"
    }
}
