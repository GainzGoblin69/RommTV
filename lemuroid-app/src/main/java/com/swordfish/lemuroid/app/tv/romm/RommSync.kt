package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.net.Uri
import android.os.Build
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommGameFiles
import com.swordfish.lemuroid.lib.romm.RommLocalSave
import com.swordfish.lemuroid.lib.romm.RommPlaySession
import com.swordfish.lemuroid.lib.romm.RommRomDetail
import com.swordfish.lemuroid.lib.romm.RommSave
import com.swordfish.lemuroid.lib.romm.RommSession
import com.swordfish.lemuroid.lib.romm.RommState
import com.swordfish.lemuroid.lib.romm.RommSyncOp
import com.swordfish.lemuroid.lib.saves.SaveState
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Keeps this TV's saves and states in step with RomM, the same way RomM's own web player does:
 *  - in-game saves (.srm) live in the "autosave" slot under the web player's core name, so the TV and the
 *    website share one save and RomM's sync decides who has the newer one;
 *  - play time is reported to RomM after every session;
 *  - the TV's save states upload after playing (one RomM state per TV slot, updated in place),
 *    and any RomM state can be loaded from the save picker.
 */
class RommSync(context: Context, private val statesManager: StatesManager) {
    private val appContext = context.applicationContext
    private val session = RommSession(appContext)
    private val client = RommClient(session)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What happened before a game started. */
    data class BeforePlay(
        val sessionId: Int,
        val local: RommLocalSave?,
        /** RomM and the TV both changed this save: the player chooses. */
        val conflict: RommSyncOp?,
        /** A newer save came from RomM, so the game should start from it, not from the TV's last position. */
        val downloaded: Boolean,
        val message: String?,
        val completed: Int,
        val failed: Int,
    )

    fun deviceId(): String {
        session.deviceId?.let { return it }
        val id = client.registerDevice("RomM TV on ${Build.MANUFACTURER} ${Build.MODEL}".trim(), BuildConfig.VERSION_NAME)
        session.saveDeviceId(id)
        return id
    }

    /** Asks RomM how the TV's save compares, and downloads or uploads it unless both sides changed. */
    fun beforePlay(
        game: Game,
        info: RommRomDetail,
    ): BeforePlay {
        val device = deviceId()
        val local = localSave(game, info)
        val plan = client.negotiate(device, info.id, listOfNotNull(local), cores(info))
        val op = plan.operations.firstOrNull { it.slot == SLOT && it.action != "no_op" }
        var downloaded = false
        var message: String? = null
        var completed = 0
        var failed = 0
        when (op?.action) {
            "download" ->
                runCatching { download(game, op, device, plan.sessionId) }
                    .onSuccess {
                        downloaded = true
                        completed++
                        message = "Loaded your RomM save from ${GameDates.dateTime(op.serverUpdatedAt)}."
                    }.onFailure {
                        failed++
                        Timber.e(it, "Save download failed")
                        message = "Couldn't get the newer save from RomM: ${it.message}"
                    }
            "upload" ->
                runCatching { client.uploadSyncedSave(local!!, saveFile(game), device, overwrite = false) }
                    .onSuccess { completed++ }
                    .onFailure {
                        failed++
                        Timber.e(it, "Save upload failed")
                    }
            "conflict" -> return BeforePlay(plan.sessionId, local, op, false, null, 0, 0)
        }
        return BeforePlay(plan.sessionId, local, null, downloaded, message, completed, failed)
    }

    /** The player chose between the TV's save and RomM's after a conflict. Returns true if RomM's was loaded. */
    fun resolveConflict(
        game: Game,
        before: BeforePlay,
        useRomm: Boolean,
    ): Boolean {
        val device = deviceId()
        val op = before.conflict ?: return false
        return if (useRomm) {
            download(game, op, device, before.sessionId)
            true
        } else {
            before.local?.let { client.uploadSyncedSave(it, saveFile(game), device, overwrite = true) }
            false
        }
    }

    fun finishBeforePlay(
        before: BeforePlay,
        completed: Int,
        failed: Int,
    ) {
        runCatching { client.completeSync(before.sessionId, completed, failed, emptyList()) }
            .onFailure { Timber.e(it, "Couldn't close the sync session") }
    }

    /**
     * After playing: uploads the TV's save if it changed, uploads changed states, and reports the play time.
     * Anything that doesn't get through (no Wi-Fi, server down) is queued and retried in the background.
     * Returns a line for the game page, or null when there's nothing to say.
     */
    suspend fun afterPlay(
        game: Game,
        info: RommRomDetail,
        coreID: CoreID,
        startMillis: Long,
        endMillis: Long,
        durationMillis: Long,
    ): String? {
        val play =
            RommPlaySession(info.id, SLOT, startMillis, endMillis, durationMillis.coerceAtLeast(1000))
                .takeIf { endMillis - startMillis >= 1000 }
        val plays = pendingPlays(game.fileUri) + listOfNotNull(play)
        val result = upload(game, info, coreID, plays)
        if (result.done) {
            clearPending(game.fileUri)
        } else {
            queue(game.fileUri, if (result.playsReported) emptyList() else plays)
        }
        return result.notes.joinToString(" ").ifBlank { null }
    }

    /** Retries a game whose upload didn't get through. True when everything is now on RomM. */
    suspend fun retry(
        game: Game,
        info: RommRomDetail,
        coreID: CoreID,
    ): Boolean {
        val plays = pendingPlays(game.fileUri)
        val result = upload(game, info, coreID, plays)
        if (result.done) clearPending(game.fileUri) else queue(game.fileUri, if (result.playsReported) emptyList() else plays)
        return result.done
    }

    private class Uploaded(val notes: List<String>, val done: Boolean, val playsReported: Boolean)

    private suspend fun upload(
        game: Game,
        info: RommRomDetail,
        coreID: CoreID,
        plays: List<RommPlaySession>,
    ): Uploaded {
        val notes = mutableListOf<String>()
        var saveOk = false
        var reported = plays.isEmpty()
        val device = runCatching { deviceId() }.onFailure { Timber.e(it, "Couldn't register this TV") }.getOrNull()

        if (device != null) {
            runCatching {
                val local = localSave(game, info)
                val plan = client.negotiate(device, info.id, listOfNotNull(local), cores(info))
                val op = plan.operations.firstOrNull { it.slot == SLOT && it.action != "no_op" }
                var completed = 0
                var failed = 0
                saveOk = true
                // The TV just played, so its save is the one to keep. If RomM's also changed meanwhile,
                // RomM keeps that version in the slot's history.
                if (local != null && (op?.action == "upload" || op?.action == "conflict")) {
                    runCatching { client.uploadSyncedSave(local, saveFile(game), device, overwrite = op.action == "conflict") }
                        .onSuccess {
                            completed++
                            notes += "Your save was synced to RomM."
                        }.onFailure {
                            failed++
                            saveOk = false
                            Timber.e(it, "Save upload failed")
                        }
                }
                runCatching {
                    client.completeSync(plan.sessionId, completed, failed, plays)
                    reported = true
                }.onFailure { Timber.e(it, "Couldn't close the sync session") }
            }.onFailure { Timber.e(it, "Sync after play failed") }
        }
        if (!reported) {
            runCatching { client.reportPlaySessions(device, plays) }
                .onSuccess { reported = true }
                .onFailure { Timber.e(it, "Couldn't report play time") }
        }

        val states = runCatching { uploadStates(game, info, coreID) }.getOrElse { Timber.e(it, "State upload failed"); -1 }
        if (states > 0) notes += "$states save state${if (states == 1) "" else "s"} synced to RomM."

        val done = saveOk && states >= 0 && reported
        if (!done) notes += "Couldn't reach RomM to sync everything. It's saved on this ${com.swordfish.lemuroid.lib.romm.Device.word} and will upload automatically when RomM is reachable."
        return Uploaded(notes, done, reported)
    }

    // ---- the retry queue: games whose save, states or play time haven't reached RomM yet

    fun pendingGames(): Set<String> = prefs.getStringSet(KEY_PENDING, emptySet())!!.toSet()

    private fun queue(
        fileUri: String,
        plays: List<RommPlaySession>,
    ) {
        val array = org.json.JSONArray()
        plays.forEach {
            array.put(
                org.json.JSONObject()
                    .put("rom", it.romId)
                    .put("start", it.startMillis)
                    .put("end", it.endMillis)
                    .put("duration", it.durationMillis),
            )
        }
        prefs.edit()
            .putStringSet(KEY_PENDING, pendingGames() + fileUri)
            .putString(KEY_PLAYS + fileUri, array.toString())
            .commit()
        RommSyncRetryWork.enqueue(appContext)
    }

    private fun clearPending(fileUri: String) {
        prefs.edit()
            .putStringSet(KEY_PENDING, pendingGames() - fileUri)
            .remove(KEY_PLAYS + fileUri)
            .commit()
    }

    private fun pendingPlays(fileUri: String): List<RommPlaySession> =
        runCatching {
            val array = org.json.JSONArray(prefs.getString(KEY_PLAYS + fileUri, "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RommPlaySession(o.getInt("rom"), SLOT, o.getLong("start"), o.getLong("end"), o.getLong("duration"))
            }
        }.getOrDefault(emptyList())

    /** Forgets a queued game that no longer exists in the library. */
    fun dropPending(fileUri: String) = clearPending(fileUri)

    /** A specific RomM save, chosen in the picker, replaces the TV's. */
    fun useServerSave(
        game: Game,
        save: RommSave,
    ) {
        val device = deviceId()
        val local = saveFile(game)
        val incoming = File(local.parentFile, local.name + ".incoming")
        client.downloadSave(save.id, incoming)
        install(local, incoming)
        runCatching { client.confirmDownloaded(save.id, device, md5(local)) }
    }

    /** A RomM state, chosen in the picker, becomes the TV's resume point for the game. */
    suspend fun useServerState(
        game: Game,
        coreID: CoreID,
        state: RommState,
    ) {
        val temp = File(appContext.cacheDir, "romm-state-${state.id}")
        try {
            client.downloadState(state.id, temp)
            val bytes = temp.readBytes()
            if (bytes.isEmpty()) error("The state came back empty")
            statesManager.setAutoSave(game, coreID, SaveState(bytes, SaveState.Metadata()))
        } finally {
            temp.delete()
        }
    }

    /** States this TV uploaded, so the picker can label them. */
    fun uploadedStateIds(romId: Int): Set<Int> =
        (0..StatesManager.MAX_STATES).mapNotNull { prefs.getInt(stateKey(romId, it, "id"), -1).takeIf { id -> id > 0 } }.toSet()

    // ---- states

    /** Uploads the TV's auto state and its slots that changed since last time. Returns how many were sent. */
    private suspend fun uploadStates(
        game: Game,
        info: RommRomDetail,
        coreID: CoreID,
    ): Int {
        val emulator = cores(info).first()
        var sent = 0
        val slots = listOf<Pair<Int, SaveState?>>(0 to statesManager.getAutoSave(game, coreID)) +
            (0 until StatesManager.MAX_STATES).map { (it + 1) to statesManager.getSlotSave(game, coreID, it) }
        for ((index, state) in slots) {
            val bytes = state?.state?.takeIf { it.isNotEmpty() } ?: continue
            val hash = md5(bytes)
            if (prefs.getString(stateKey(info.id, index, "hash"), null) == hash) continue
            val name = "${game.title.take(60)} - ${com.swordfish.lemuroid.lib.romm.Device.word} ${if (index == 0) "autosave" else "slot $index"}.state"
            val known = prefs.getInt(stateKey(info.id, index, "id"), -1)
            val id =
                if (known > 0 && client.updateState(known, name, bytes, null)) {
                    known
                } else {
                    client.uploadState(info.id, emulator, name, bytes, null)
                }
            prefs.edit().putInt(stateKey(info.id, index, "id"), id).putString(stateKey(info.id, index, "hash"), hash).apply()
            sent++
        }
        return sent
    }

    private fun stateKey(
        romId: Int,
        index: Int,
        what: String,
    ) = "state:$romId:$index:$what"

    // ---- the TV's save file

    fun saveFile(game: Game): File =
        File(DirectoriesManager(appContext).getSavesDirectory(), "${game.fileName.substringBeforeLast(".")}.srm")

    private fun localSave(
        game: Game,
        info: RommRomDetail,
    ): RommLocalSave? {
        val file = saveFile(game)
        if (!file.exists() || file.length() == 0L) return null
        return RommLocalSave(
            romId = info.id,
            fileName = "${info.title.take(80)}.srm".replace('/', '-'),
            slot = SLOT,
            emulator = cores(info).first(),
            md5 = md5(file),
            updatedAtMillis = file.lastModified(),
            size = file.length(),
        )
    }

    private fun download(
        game: Game,
        op: RommSyncOp,
        device: String,
        sessionId: Int,
    ) {
        val saveId = op.saveId ?: error("RomM didn't say which save to download")
        val local = saveFile(game)
        val incoming = File(local.parentFile, local.name + ".incoming")
        client.downloadSaveForDevice(saveId, device, sessionId, incoming)
        install(local, incoming)
        client.confirmDownloaded(saveId, device, md5(local))
    }

    /** Puts a downloaded save in place. The TV's previous save is kept beside it as .bak. */
    private fun install(
        local: File,
        incoming: File,
    ) {
        if (incoming.length() == 0L) {
            incoming.delete()
            error("The save came back empty")
        }
        local.parentFile?.mkdirs()
        if (local.exists()) local.copyTo(File(local.parentFile, local.name + ".bak"), overwrite = true)
        incoming.copyTo(local, overwrite = true)
        incoming.delete()
    }

    companion object {
        private const val PREFS = "romm_sync"
        private const val KEY_PENDING = "pending_games"
        private const val KEY_PLAYS = "pending_plays:"

        /** The web player's main save slot; using the same one is what makes TV and website share a save. */
        const val SLOT = "autosave"

        fun hasPending(context: Context): Boolean =
            !context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_PENDING, emptySet()).isNullOrEmpty()

        fun romId(game: Game): Int? =
            Uri.parse(game.fileUri).takeIf { it.scheme == RommGameFiles.SCHEME }?.let { RommGameFiles.romId(it) }

        /**
         * The web player's cores for a RomM platform, its default first. Saves are filed under these names,
         * so the TV's saves show up in the web player and the web player's saves come to the TV.
         */
        private val WEB_CORES =
            mapOf(
                "nes" to listOf("fceumm", "nestopia"),
                "famicom" to listOf("fceumm", "nestopia"),
                "snes" to listOf("snes9x"),
                "sfam" to listOf("snes9x"),
                "gb" to listOf("gambatte", "mgba"),
                "gbc" to listOf("gambatte", "mgba"),
                "gba" to listOf("mgba"),
                "n64" to listOf("mupen64plus_next", "parallel_n64"),
                "nds" to listOf("melonds", "desmume", "desmume2015"),
                "genesis" to listOf("genesis_plus_gx"),
                "sms" to listOf("genesis_plus_gx"),
                "gamegear" to listOf("genesis_plus_gx"),
                "segacd" to listOf("genesis_plus_gx", "picodrive"),
                "psx" to listOf("pcsx_rearmed", "mednafen_psx_hw"),
                "psp" to listOf("ppsspp"),
                "tg16" to listOf("mednafen_pce"),
                "turbografx-cd" to listOf("mednafen_pce"),
                "atari2600" to listOf("stella2014"),
                "atari7800" to listOf("prosystem"),
                "lynx" to listOf("handy"),
                "neo-geo-pocket" to listOf("mednafen_ngp"),
                "neo-geo-pocket-color" to listOf("mednafen_ngp"),
                "wonderswan" to listOf("mednafen_wswan"),
                "wonderswan-color" to listOf("mednafen_wswan"),
                "arcade" to listOf("fbneo"),
                "neogeoaes" to listOf("fbneo"),
                "neogeomvs" to listOf("fbneo"),
            )

        fun cores(info: RommRomDetail): List<String> =
            WEB_CORES[info.platformSlug.lowercase()] ?: WEB_CORES[info.platformFsSlug.lowercase()] ?: listOf("romm-tv")

        fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
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

        fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Dates from RomM, written the way the game page shows them. */
object GameDates {
    fun dateTime(iso: String?): String =
        runCatching {
            val parser =
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
            val parsed = parser.parse(iso!!.take(19).replace(' ', 'T'))!!
            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(parsed)
        }.getOrDefault("earlier")
}
