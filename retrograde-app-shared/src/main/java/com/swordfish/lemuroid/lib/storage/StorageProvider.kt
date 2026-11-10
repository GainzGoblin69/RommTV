/*
 * GameLibraryProvider.kt
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

package com.swordfish.lemuroid.lib.storage

import android.net.Uri
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface StorageProvider {
    val id: String

    val name: String

    val uriSchemes: List<String>

    val prefsFragmentClass: Class<out LeanbackPreferenceFragment>?

    val enabledByDefault: Boolean

    fun listBaseStorageFiles(): Flow<List<BaseStorageFile>>

    fun getInputStream(uri: Uri): InputStream?

    fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile?

    fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean,
    ): RomFiles

    /** How listed files group into games. Null: work it out from the files (bin/cue, m3u). */
    fun groupFiles(files: List<BaseStorageFile>): List<GroupedStorageFiles>? = null

    /** Providers that already know each game's system, title and cover (a RomM server) build it directly. */
    fun buildGame(
        files: GroupedStorageFiles,
        lastIndexedAt: Long,
    ): Game? = null

    /** A game found again on a later sync: take any new title or cover. */
    fun refreshGame(
        game: Game,
        files: GroupedStorageFiles,
        lastIndexedAt: Long,
    ): Game = game.copy(lastIndexedAt = lastIndexedAt)
}
