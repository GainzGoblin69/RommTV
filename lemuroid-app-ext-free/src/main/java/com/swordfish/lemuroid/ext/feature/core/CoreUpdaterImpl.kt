/*
 * CoreManager.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.ext.feature.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
) : CoreUpdater {
    // This is the last tagged versions of cores.
    companion object {
        private const val CORES_VERSION = "1.17.0"

        private val BUILDBOT_CORES = setOf(CoreID.FLYCAST, CoreID.YABASANSHIRO, CoreID.DOLPHIN)
    }

    private val baseUri = Uri.parse("https://github.com/Swordfish90/LemuroidCores/")

    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

    override suspend fun downloadCores(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        coreIDs.asFlow()
            .onEach { retrieveAssets(it, sharedPreferences) }
            .onEach { retrieveFile(context, it) }
            .collect()
    }

    private suspend fun retrieveFile(
        context: Context,
        coreID: CoreID,
    ) {
        findBundledLibrary(context, coreID)
            ?: if (coreID in BUILDBOT_CORES) downloadCoreFromBuildbot(coreID) else downloadCoreFromGithub(coreID)
    }

    /**
     * Cores Lemuroid doesn't ship (Dreamcast, Saturn, GameCube/Wii) come from libretro's build server, the same
     * place RetroArch gets them. Each is a zip holding one .so; kept under the same name scheme as the others.
     */
    private suspend fun downloadCoreFromBuildbot(coreID: CoreID): File {
        val coresDirectory = File(directoriesManager.getCoresDirectory(), "buildbot").apply { mkdirs() }
        val destFile = File(coresDirectory, coreID.libretroFileName)
        if (destFile.exists() && destFile.length() > 0) return destFile

        val abi = Build.SUPPORTED_ABIS.first()
        val url = "https://buildbot.libretro.com/nightly/android/latest/$abi/${coreID.coreName}_libretro_android.so.zip"
        Timber.i("Downloading core $coreID from $url")
        try {
            val response = api.downloadZip(url)
            if (!response.isSuccessful) {
                throw Exception("Couldn't download the ${coreID.coreDisplayName} emulator (HTTP ${response.code()})")
            }
            response.body()?.use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".so")) {
                        destFile.outputStream().use { zip.copyTo(it) }
                        break
                    }
                }
            }
            if (!destFile.exists() || destFile.length() == 0L) throw Exception("The ${coreID.coreDisplayName} download was empty")
            return destFile
        } catch (e: Throwable) {
            destFile.safeDelete()
            throw e
        }
    }

    private suspend fun retrieveAssets(
        coreID: CoreID,
        sharedPreferences: SharedPreferences,
    ) {
        CoreID.getAssetManager(coreID)
            .retrieveAssetsIfNeeded(api, directoriesManager, sharedPreferences)
    }

    private suspend fun downloadCoreFromGithub(coreID: CoreID): File {
        Timber.i("Downloading core $coreID from github")

        val mainCoresDirectory = directoriesManager.getCoresDirectory()
        val coresDirectory =
            File(mainCoresDirectory, CORES_VERSION).apply {
                mkdirs()
            }

        val libFileName = coreID.libretroFileName
        val destFile = File(coresDirectory, libFileName)

        if (destFile.exists()) {
            return destFile
        }

        runCatching {
            deleteOutdatedCores(mainCoresDirectory, CORES_VERSION)
        }

        val uri =
            baseUri.buildUpon()
                .appendEncodedPath("raw/$CORES_VERSION/lemuroid_core_${coreID.coreName}/src/main/jniLibs/")
                .appendPath(Build.SUPPORTED_ABIS.first())
                .appendPath(libFileName)
                .build()

        try {
            downloadFile(uri, destFile)
            return destFile
        } catch (e: Throwable) {
            destFile.safeDelete()
            throw e
        }
    }

    private suspend fun downloadFile(
        uri: Uri,
        destFile: File,
    ) {
        val response = api.downloadFile(uri.toString())

        if (!response.isSuccessful) {
            Timber.e("Download core response was unsuccessful")
            throw Exception(response.errorBody()?.string() ?: "Download error")
        }

        response.body()?.writeToFile(destFile)
    }

    private suspend fun findBundledLibrary(
        context: Context,
        coreID: CoreID,
    ): File? =
        withContext(Dispatchers.IO) {
            File(context.applicationInfo.nativeLibraryDir)
                .walkBottomUp()
                .firstOrNull { it.name == coreID.libretroFileName }
        }

    private fun deleteOutdatedCores(
        mainCoresDirectory: File,
        applicationVersion: String,
    ) {
        mainCoresDirectory.listFiles()
            ?.filter { it.name != applicationVersion && it.name != "buildbot" }
            ?.forEach { it.deleteRecursively() }
    }
}
