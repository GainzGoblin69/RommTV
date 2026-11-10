package com.swordfish.lemuroid.lib.storage.romm

import android.content.Context
import android.net.Uri
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommGameFiles
import com.swordfish.lemuroid.lib.romm.RommPlatforms
import com.swordfish.lemuroid.lib.romm.RommRom
import com.swordfish.lemuroid.lib.romm.RommSession
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.GroupedStorageFiles
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The game library comes from the RomM server. Listing only reads the catalogue (titles, systems, covers);
 * a game's files are downloaded the first time it is played.
 */
class RommStorageProvider(private val context: Context) : StorageProvider {
    private val session = RommSession(context)
    private val client = RommClient(session)

    /** What the last listing learned about each game, by its uri. */
    private val known = ConcurrentHashMap<String, RommRom>()

    override val id: String = "romm"

    override val name: String = "RomM"

    override val uriSchemes = listOf(RommGameFiles.SCHEME)

    override val prefsFragmentClass: Class<LeanbackPreferenceFragment>? = null

    override val enabledByDefault = true

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> =
        flow {
            if (!session.isSignedIn) return@flow
            known.clear()
            var offset = 0
            while (true) {
                // A failure here stops the sync and keeps the library as it was (see LemuroidLibrary)
                val (page, total) = client.roms(offset, PAGE_SIZE)
                val playable =
                    page.filter { RommPlatforms.systemFor(it.platformSlug, it.platformFsSlug) != null }
                val files =
                    playable.map { rom ->
                        val uri = RommGameFiles.gameUri(rom.id, rom.platformId)
                        known[uri.toString()] = rom
                        BaseStorageFile(
                            name = rom.fsName.ifBlank { rom.title },
                            size = rom.size,
                            uri = uri,
                            path = null,
                        )
                    }
                Timber.i("RomM: ${files.size} playable of ${page.size} (offset $offset of $total)")
                emit(files)
                offset += page.size
                if (page.isEmpty() || offset >= total) break
            }
        }

    override fun groupFiles(files: List<BaseStorageFile>): List<GroupedStorageFiles> = files.map { GroupedStorageFiles(it, emptyList()) }

    override fun buildGame(
        files: GroupedStorageFiles,
        lastIndexedAt: Long,
    ): Game? {
        val rom = known[files.primaryFile.uri.toString()] ?: return null
        val system = RommPlatforms.systemFor(rom.platformSlug, rom.platformFsSlug) ?: return null
        return Game(
            fileName = files.primaryFile.name,
            fileUri = files.primaryFile.uri.toString(),
            title = rom.title,
            systemId = system.dbname,
            developer = null,
            coverFrontUrl = client.coverUrl(rom),
            lastIndexedAt = lastIndexedAt,
        )
    }

    override fun refreshGame(
        game: Game,
        files: GroupedStorageFiles,
        lastIndexedAt: Long,
    ): Game {
        val fresh = buildGame(files, lastIndexedAt) ?: return game.copy(lastIndexedAt = lastIndexedAt)
        return game.copy(
            title = fresh.title,
            systemId = fresh.systemId,
            coverFrontUrl = fresh.coverFrontUrl,
            lastIndexedAt = lastIndexedAt,
        )
    }

    override fun getInputStream(uri: Uri): InputStream? = null

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile? = null

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean,
    ): RomFiles {
        val romId = RommGameFiles.romId(Uri.parse(game.fileUri)) ?: error("Not a RomM game: ${game.fileUri}")
        val file = RommGameFiles(context).prepare(romId, game.systemId)
        return RomFiles.Standard(listOf(file))
    }

    companion object {
        private const val PAGE_SIZE = 500
    }
}
