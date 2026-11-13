package com.swordfish.lemuroid.lib.romm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class RommException(message: String, val code: Int = 0) : IOException(message)

data class RommPlatform(
    val id: Int,
    val slug: String,
    val fsSlug: String,
    val name: String,
    val romCount: Int,
)

data class RommRomFile(
    val id: Int,
    val fileName: String,
    val size: Long,
    val isTopLevel: Boolean,
    val crc: String?,
)

data class RommRom(
    val id: Int,
    val platformId: Int,
    val platformSlug: String,
    val platformFsSlug: String,
    val title: String,
    val fsName: String,
    val size: Long,
    val coverPath: String?,
    val coverUrl: String?,
    val files: List<RommRomFile>,
)

data class RommFirmware(
    val id: Int,
    val fileName: String,
    val size: Long,
    val crc: String?,
    val md5: String?,
)

data class RommSave(
    val id: Int,
    val fileName: String,
    val size: Long,
    val emulator: String?,
    val slot: String?,
    val updatedAt: String,
)

data class RommState(
    val id: Int,
    val fileName: String,
    val size: Long,
    val emulator: String?,
    val updatedAt: String,
)

/** One step RomM's sync asks this device to take for a save slot. */
data class RommSyncOp(
    val action: String,
    val romId: Int,
    val saveId: Int?,
    val fileName: String,
    val slot: String?,
    val emulator: String?,
    val reason: String?,
    val serverUpdatedAt: String?,
    val serverHash: String?,
)

data class RommSyncPlan(val sessionId: Int, val operations: List<RommSyncOp>)

/** A save as this device holds it, for sync negotiation. */
data class RommLocalSave(
    val romId: Int,
    val fileName: String,
    val slot: String,
    val emulator: String,
    val md5: String,
    val updatedAtMillis: Long,
    val size: Long,
)

data class RommPlaySession(
    val romId: Int,
    val slot: String?,
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
)

data class RommAchievement(
    val badgeId: String?,
    val title: String,
    val description: String,
    val points: Int,
    val badgeUrl: String?,
    val badgeUrlLock: String?,
    val badgePath: String?,
    val badgePathLock: String?,
    val order: Int,
    val numAwarded: Int,
    val type: String?,
)

data class RommRomDetail(
    val id: Int,
    val raId: Int?,
    val title: String,
    val platformName: String,
    val summary: String?,
    val genres: List<String>,
    val companies: List<String>,
    val gameModes: List<String>,
    val releaseDate: Long?,
    val rating: Double?,
    val playerCount: String?,
    val regions: List<String>,
    val hltbMain: Int?,
    val hltbExtra: Int?,
    val hltbComplete: Int?,
    val screenshots: List<String>,
    val achievements: List<RommAchievement>,
    val saves: List<RommSave>,
    val states: List<RommState>,
    val platformSlug: String,
    val platformFsSlug: String,
    val lastPlayed: String?,
    val sizeBytes: Long,
)

data class RommDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

sealed class RommTokenPoll {
    object Pending : RommTokenPoll()

    object SlowDown : RommTokenPoll()

    object Denied : RommTokenPoll()

    object Expired : RommTokenPoll()

    data class Approved(val token: String, val deviceId: String?) : RommTokenPoll()
}

/** Plain blocking calls to the RomM 5.x API. Call them off the main thread. */
class RommClient(private val session: RommSession) {
    private fun base(): HttpUrl = session.serverUrl.toHttpUrl()

    private fun url(
        path: String,
        server: String? = null,
    ): HttpUrl.Builder {
        val root = (server?.let { RommSession.normalizeServer(it) } ?: session.serverUrl).toHttpUrl()
        return root.newBuilder().addPathSegments(path.trimStart('/'))
    }

    private fun authed(builder: Request.Builder): Request.Builder {
        session.token?.let { builder.header("Authorization", "Bearer $it") }
        return builder.header("Accept", "application/json")
    }

    private fun execute(request: Request): Response {
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
            response.close()
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
            throw RommException(
                when (response.code) {
                    401 -> "Your RomM sign-in has expired. Sign in again from Settings."
                    403 -> "Your RomM account doesn't allow this (${request.url.encodedPath})."
                    404 -> "Not found on your RomM server."
                    else -> "RomM server error ${response.code}${if (detail.isNotBlank()) ": $detail" else ""}"
                },
                response.code,
            )
        }
        return response
    }

    private fun getJson(url: HttpUrl): String {
        val request = authed(Request.Builder().url(url)).get().build()
        return execute(request).use { it.body!!.string() }
    }

    // ---- sign in (RomM's device login: the TV shows a code, the owner approves it in RomM)

    fun startDeviceLogin(
        server: String,
        clientIdentifier: String,
        deviceName: String,
        appVersion: String,
    ): RommDeviceCode {
        val body =
            JSONObject()
                .put("client_device_identifier", clientIdentifier)
                .put("name", deviceName.take(255))
                .put("client", "romm-tv")
                .put("platform", "android-tv")
                .put("client_version", appVersion.take(50))
                .put("requested_scopes", JSONArray(REQUESTED_SCOPES))
        val request =
            Request.Builder()
                .url(url("api/auth/device/init", server).build())
                .post(body.toString().toRequestBody(JSON))
                .build()
        val json = execute(request).use { JSONObject(it.body!!.string()) }
        val origin = RommSession.normalizeServer(server)
        return RommDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = origin + json.getString("verification_path"),
            verificationUrlComplete = origin + json.getString("verification_path_complete"),
            expiresInSeconds = json.optInt("expires_in", 600),
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(2),
        )
    }

    fun pollDeviceLogin(
        server: String,
        deviceCode: String,
    ): RommTokenPoll {
        val body = JSONObject().put("device_code", deviceCode)
        val request =
            Request.Builder()
                .url(url("api/auth/device/token", server).build())
                .post(body.toString().toRequestBody(JSON))
                .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(text)
                return RommTokenPoll.Approved(json.getString("access_token"), json.optString("device_id").ifBlank { null })
            }
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
            return when (detail) {
                "authorization_pending" -> RommTokenPoll.Pending
                "slow_down" -> RommTokenPoll.SlowDown
                "access_denied" -> RommTokenPoll.Denied
                "expired_token" -> RommTokenPoll.Expired
                else -> if (response.code == 429) RommTokenPoll.SlowDown else throw RommException("Sign-in failed (${response.code})", response.code)
            }
        }
    }

    // ---- library

    fun platforms(): List<RommPlatform> {
        val array = JSONArray(getJson(url("api/platforms").build()))
        return (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            RommPlatform(
                id = p.getInt("id"),
                slug = p.optString("slug"),
                fsSlug = p.optString("fs_slug"),
                name = p.optString("display_name").ifBlank { p.optString("name") },
                romCount = p.optInt("rom_count"),
            )
        }
    }

    /** One page of games, with their files. Returns the games and the total on the server. */
    /** One page of the library. [received] is how many entries the server sent, including ones the app skips. */
    class RomPage(val roms: List<RommRom>, val received: Int, val total: Int)

    fun roms(
        offset: Int,
        limit: Int,
    ): RomPage {
        val url =
            url("api/roms")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("with_files", "true")
                // Ordered by id: every game has its own, so pages never overlap or skip a game.
                // (Ordering by name let games with the same name swap places between pages.)
                .addQueryParameter("order_by", "id")
                .addQueryParameter("order_dir", "asc")
                // Extras the website uses for its gallery; leaving them out makes each page much faster
                .addQueryParameter("with_char_index", "false")
                .addQueryParameter("with_filter_values", "false")
                .addQueryParameter("with_rom_id_index", "false")
                .build()
        val json = JSONObject(getJson(url))
        val items = json.getJSONArray("items")
        val roms =
            (0 until items.length()).mapNotNull { i ->
                runCatching { parseRom(items.getJSONObject(i)) }
                    .onFailure { timber.log.Timber.e(it, "Skipping a game RomM listed in an unexpected shape") }
                    .getOrNull()
            }
        return RomPage(roms, items.length(), json.optInt("total", offset + items.length()))
    }

    private fun parseRom(r: JSONObject): RommRom? {
        if (r.optBoolean("missing_from_fs", false)) return null
        val filesJson = r.optJSONArray("files") ?: JSONArray()
        val files =
            (0 until filesJson.length()).map { i ->
                val f = filesJson.getJSONObject(i)
                RommRomFile(
                    id = f.getInt("id"),
                    fileName = f.getString("file_name"),
                    size = f.optLong("file_size_bytes"),
                    isTopLevel = f.optBoolean("is_top_level", true),
                    crc = f.optStringOrNull("crc_hash"),
                )
            }
        return RommRom(
            id = r.getInt("id"),
            platformId = r.getInt("platform_id"),
            platformSlug = r.optString("platform_slug"),
            platformFsSlug = r.optString("platform_fs_slug"),
            title = r.optStringOrNull("name") ?: r.optString("fs_name_no_ext"),
            fsName = r.optString("fs_name"),
            size = r.optLong("fs_size_bytes"),
            coverPath = r.optStringOrNull("path_cover_large"),
            coverUrl = r.optStringOrNull("url_cover"),
            files = files,
        )
    }

    fun firmware(platformId: Int): List<RommFirmware> {
        val url = url("api/firmware").addQueryParameter("platform_id", platformId.toString()).build()
        val array = JSONArray(getJson(url))
        return (0 until array.length()).map { i ->
            val f = array.getJSONObject(i)
            RommFirmware(
                id = f.getInt("id"),
                fileName = f.getString("file_name"),
                size = f.optLong("file_size_bytes"),
                crc = f.optStringOrNull("crc_hash"),
                md5 = f.optStringOrNull("md5_hash"),
            )
        }
    }

    /**
     * The cover's public address on the site RomM scraped it from (SteamGridDB, IGDB...) comes first: some RomM
     * installs (Whatbox's) don't serve their saved copies under /assets/romm/resources. The server's copy is the
     * fallback, for covers that were uploaded by hand.
     */
    fun coverUrl(rom: RommRom): String? {
        rom.coverUrl?.takeIf { it.startsWith("http") }?.let { return it }
        val path = rom.coverPath
        if (!path.isNullOrBlank()) {
            return if (path.startsWith("http")) path else session.serverUrl + "/" + path.trimStart('/')
        }
        return null
    }

    fun romFileUrl(
        fileId: Int,
        fileName: String,
    ): HttpUrl =
        url("api/roms").addPathSegment(fileId.toString()).addPathSegment("files")
            .addPathSegment("content").addPathSegment(fileName).build()

    fun firmwareUrl(firmware: RommFirmware): HttpUrl =
        url("api/firmware").addPathSegment(firmware.id.toString()).addPathSegment("content")
            .addPathSegment(firmware.fileName).build()

    /** Downloads to a temp file and renames it, so a cut-off download never looks complete. */
    fun download(
        url: HttpUrl,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")
        val request = authed(Request.Builder().url(url)).header("Accept", "*/*").get().build()
        execute(request).use { response ->
            val body = response.body ?: throw RommException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 1_000_000) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        }
        dest.delete()
        if (!temp.renameTo(dest)) throw RommException("Couldn't save ${dest.name}")
    }

    // ---- one game's page: details, achievements, saves

    fun romDetail(romId: Int): RommRomDetail {
        val r = JSONObject(getJson(url("api/roms").addPathSegment(romId.toString()).build()))
        val meta = r.optJSONObject("metadatum")
        val hltb = r.optJSONObject("hltb_metadata")
        val ra = r.optJSONObject("merged_ra_metadata")?.optJSONArray("achievements") ?: JSONArray()
        val saves = r.optJSONArray("user_saves") ?: JSONArray()
        val shots = r.optJSONArray("merged_screenshots") ?: JSONArray()

        fun strings(
            o: JSONObject?,
            key: String,
        ): List<String> {
            val a = o?.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        }

        fun seconds(key: String): Int? = hltb?.optInt(key, 0)?.takeIf { it > 0 }

        return RommRomDetail(
            id = r.getInt("id"),
            raId = if (r.isNull("ra_id")) null else r.optInt("ra_id"),
            title = r.optStringOrNull("name") ?: r.optString("fs_name_no_ext"),
            platformName = r.optString("platform_display_name"),
            summary = r.optStringOrNull("summary"),
            genres = strings(meta, "genres"),
            companies = strings(meta, "companies"),
            gameModes = strings(meta, "game_modes"),
            releaseDate = meta?.let { if (it.isNull("first_release_date")) null else it.optLong("first_release_date") },
            rating = meta?.let { if (it.isNull("average_rating")) null else it.optDouble("average_rating") },
            playerCount = meta?.optStringOrNull("player_count"),
            regions = strings(r, "regions"),
            hltbMain = seconds("main_story"),
            hltbExtra = seconds("main_plus_extra"),
            hltbComplete = seconds("completionist"),
            screenshots = (0 until shots.length()).mapNotNull { shots.optString(it).ifBlank { null } },
            achievements =
                (0 until ra.length()).map { i ->
                    val a = ra.getJSONObject(i)
                    RommAchievement(
                        badgeId = a.optStringOrNull("badge_id"),
                        title = a.optStringOrNull("title") ?: "",
                        description = a.optStringOrNull("description") ?: "",
                        points = a.optInt("points"),
                        badgeUrl = a.optStringOrNull("badge_url"),
                        badgeUrlLock = a.optStringOrNull("badge_url_lock"),
                        badgePath = a.optStringOrNull("badge_path"),
                        badgePathLock = a.optStringOrNull("badge_path_lock"),
                        order = a.optInt("display_order"),
                        numAwarded = a.optInt("num_awarded"),
                        type = a.optStringOrNull("type"),
                    )
                }.sortedBy { it.order },
            saves =
                (0 until saves.length()).map { i ->
                    val v = saves.getJSONObject(i)
                    RommSave(
                        id = v.getInt("id"),
                        fileName = v.optString("file_name"),
                        size = v.optLong("file_size_bytes"),
                        emulator = v.optStringOrNull("emulator"),
                        slot = v.optStringOrNull("slot"),
                        updatedAt = v.optString("updated_at"),
                    )
                }.sortedByDescending { it.updatedAt },
            states =
                (r.optJSONArray("user_states") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { i ->
                        val v = a.getJSONObject(i)
                        RommState(
                            id = v.getInt("id"),
                            fileName = v.optString("file_name"),
                            size = v.optLong("file_size_bytes"),
                            emulator = v.optStringOrNull("emulator"),
                            updatedAt = v.optString("updated_at"),
                        )
                    }.sortedByDescending { it.updatedAt }
                },
            platformSlug = r.optString("platform_slug"),
            platformFsSlug = r.optString("platform_fs_slug"),
            lastPlayed = r.optJSONObject("rom_user")?.optStringOrNull("last_played"),
            sizeBytes = r.optLong("fs_size_bytes"),
        )
    }

    /** The RetroAchievements this RomM user has earned for a game: badge id to the date it was earned. */
    fun earnedAchievements(raId: Int): Map<String, String> {
        val me = JSONObject(getJson(url("api/users/me").build()))
        val results = me.optJSONObject("ra_progression")?.optJSONArray("results") ?: return emptyMap()
        for (i in 0 until results.length()) {
            val g = results.getJSONObject(i)
            if (g.optInt("rom_ra_id", -1) != raId) continue
            val earned = g.optJSONArray("earned_achievements") ?: return emptyMap()
            return (0 until earned.length()).mapNotNull {
                val e = earned.getJSONObject(it)
                val id = e.optString("id").ifBlank { null } ?: return@mapNotNull null
                id to (e.optStringOrNull("date_hardcore") ?: e.optString("date"))
            }.toMap()
        }
        return emptyMap()
    }

    /** The RetroAchievements username set in your RomM profile, if any. */
    fun raUsername(): String? =
        JSONObject(getJson(url("api/users/me").build())).optStringOrNull("ra_username")?.takeIf { it.isNotBlank() }

    /**
     * Asks RomM to fetch your latest RetroAchievements progress (only what changed), so achievements earned
     * while playing show up on the game page. False if RomM has no RetroAchievements username for you.
     */
    fun refreshRetroAchievements(): Boolean {
        val me = JSONObject(getJson(url("api/users/me").build()))
        if (me.optStringOrNull("ra_username").isNullOrBlank()) return false
        val id = me.getInt("id")
        val body = JSONObject().put("incremental", true)
        val request =
            authed(Request.Builder().url(url("api/users").addPathSegment(id.toString()).addPathSegment("ra").addPathSegment("refresh").build()))
                .post(body.toString().toRequestBody(JSON))
                .build()
        execute(request).close()
        return true
    }

    /** How many netplay rooms are open for a game right now. */
    fun netplayRooms(romId: Int): Int =
        JSONObject(getJson(url("api/netplay/list").addQueryParameter("game_id", romId.toString()).build())).length()

    fun downloadSave(
        saveId: Int,
        dest: File,
    ) = download(url("api/saves").addPathSegment(saveId.toString()).addPathSegment("content").build(), dest)

    // ---- RomM's device sync: the server compares this TV's saves with its own and says what to do

    /** Registers this TV as a device in RomM (only needed if the sign-in didn't already). Returns its id. */
    fun registerDevice(
        name: String,
        appVersion: String,
    ): String {
        val body =
            JSONObject()
                .put("name", name)
                .put("platform", "android-tv")
                .put("client", "romm-tv")
                .put("client_version", appVersion)
                .put("allow_existing", true)
        val request = authed(Request.Builder().url(url("api/devices").build())).post(body.toString().toRequestBody(JSON)).build()
        return execute(request).use { JSONObject(it.body!!.string()).getString("id") }
    }

    fun negotiate(
        deviceId: String,
        romId: Int,
        saves: List<RommLocalSave>,
        emulators: List<String>,
    ): RommSyncPlan {
        val body =
            JSONObject()
                .put("device_id", deviceId)
                .put("rom_ids", JSONArray(listOf(romId)))
                .put("emulators", JSONArray(emulators))
                .put(
                    "saves",
                    JSONArray(
                        saves.map {
                            JSONObject()
                                .put("rom_id", it.romId)
                                .put("file_name", it.fileName)
                                .put("slot", it.slot)
                                .put("emulator", it.emulator)
                                .put("content_hash", it.md5)
                                .put("updated_at", iso(it.updatedAtMillis))
                                .put("file_size_bytes", it.size)
                        },
                    ),
                )
        val request = authed(Request.Builder().url(url("api/sync/negotiate").build())).post(body.toString().toRequestBody(JSON)).build()
        val json = execute(request).use { JSONObject(it.body!!.string()) }
        val ops = json.optJSONArray("operations") ?: JSONArray()
        return RommSyncPlan(
            sessionId = json.getInt("session_id"),
            operations =
                (0 until ops.length()).map { i ->
                    val o = ops.getJSONObject(i)
                    RommSyncOp(
                        action = o.getString("action"),
                        romId = o.getInt("rom_id"),
                        saveId = if (o.isNull("save_id")) null else o.optInt("save_id"),
                        fileName = o.optString("file_name"),
                        slot = o.optStringOrNull("slot"),
                        emulator = o.optStringOrNull("emulator"),
                        reason = o.optStringOrNull("reason"),
                        serverUpdatedAt = o.optStringOrNull("server_updated_at"),
                        serverHash = o.optStringOrNull("server_content_hash"),
                    )
                },
        )
    }

    /** Downloads a save as this device's copy (RomM records which version the TV now holds). */
    fun downloadSaveForDevice(
        saveId: Int,
        deviceId: String,
        sessionId: Int,
        dest: File,
    ) = download(
        url("api/saves").addPathSegment(saveId.toString()).addPathSegment("content")
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("session_id", sessionId.toString())
            .addQueryParameter("optimistic", "true")
            .build(),
        dest,
    )

    fun confirmDownloaded(
        saveId: Int,
        deviceId: String,
        md5: String,
    ) {
        val body = JSONObject().put("device_id", deviceId).put("content_hash", md5)
        val target = url("api/saves").addPathSegment(saveId.toString()).addPathSegment("downloaded").build()
        execute(authed(Request.Builder().url(target)).post(body.toString().toRequestBody(JSON)).build()).close()
    }

    /** Uploads this TV's save into a slot, the same way the web player does. */
    fun uploadSyncedSave(
        local: RommLocalSave,
        file: File,
        deviceId: String,
        overwrite: Boolean,
    ) {
        val target =
            url("api/saves")
                .addQueryParameter("rom_id", local.romId.toString())
                .addQueryParameter("emulator", local.emulator)
                .addQueryParameter("slot", local.slot)
                .addQueryParameter("device_id", deviceId)
                .addQueryParameter("content_hash", local.md5)
                .addQueryParameter("autocleanup", "true")
                .addQueryParameter("overwrite", overwrite.toString())
                .build()
        val body =
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("saveFile", local.fileName, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
        execute(authed(Request.Builder().url(target)).post(body).build()).close()
    }

    /** Closes a sync session, reporting play time so RomM's "last played" and play time include the TV. */
    fun completeSync(
        sessionId: Int,
        completed: Int,
        failed: Int,
        sessions: List<RommPlaySession>,
    ) {
        val body =
            JSONObject()
                .put("operations_completed", completed)
                .put("operations_failed", failed)
        if (sessions.isNotEmpty()) body.put("play_sessions", playSessionsJson(sessions))
        val target = url("api/sync/sessions").addPathSegment(sessionId.toString()).addPathSegment("complete").build()
        execute(authed(Request.Builder().url(target)).post(body.toString().toRequestBody(JSON)).build()).close()
    }

    /** Reports play time on its own, for when a sync session couldn't be used. */
    fun reportPlaySessions(
        deviceId: String?,
        sessions: List<RommPlaySession>,
    ) {
        val body = JSONObject().put("sessions", playSessionsJson(sessions))
        deviceId?.let { body.put("device_id", it) }
        execute(authed(Request.Builder().url(url("api/play-sessions").build())).post(body.toString().toRequestBody(JSON)).build()).close()
    }

    private fun playSessionsJson(sessions: List<RommPlaySession>) =
        JSONArray(
            sessions.map {
                JSONObject()
                    .put("rom_id", it.romId)
                    .put("save_slot", it.slot ?: JSONObject.NULL)
                    .put("start_time", iso(it.startMillis))
                    .put("end_time", iso(it.endMillis))
                    .put("duration_ms", it.durationMillis)
            },
        )

    // ---- save states

    fun downloadState(
        stateId: Int,
        dest: File,
    ) = download(url("api/states").addPathSegment(stateId.toString()).addPathSegment("content").build(), dest)

    /** Uploads a new state. Returns its id in RomM. */
    fun uploadState(
        romId: Int,
        emulator: String,
        fileName: String,
        bytes: ByteArray,
        screenshot: ByteArray?,
    ): Int {
        val target =
            url("api/states")
                .addQueryParameter("rom_id", romId.toString())
                .addQueryParameter("emulator", emulator)
                .build()
        val body = stateBody(fileName, bytes, screenshot)
        return execute(authed(Request.Builder().url(target)).post(body).build()).use { JSONObject(it.body!!.string()).getInt("id") }
    }

    /** Replaces an existing state's contents. Returns false if it no longer exists in RomM. */
    fun updateState(
        stateId: Int,
        fileName: String,
        bytes: ByteArray,
        screenshot: ByteArray?,
    ): Boolean {
        val target = url("api/states").addPathSegment(stateId.toString()).build()
        return try {
            execute(authed(Request.Builder().url(target)).put(stateBody(fileName, bytes, screenshot)).build()).close()
            true
        } catch (e: RommException) {
            if (e.code == 404) false else throw e
        }
    }

    private fun stateBody(
        fileName: String,
        bytes: ByteArray,
        screenshot: ByteArray?,
    ): MultipartBody {
        val builder =
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("stateFile", fileName, bytes.toRequestBody("application/octet-stream".toMediaType()))
        if (screenshot != null) {
            builder.addFormDataPart("screenshotFile", fileName.substringBeforeLast('.') + ".png", screenshot.toRequestBody("image/png".toMediaType()))
        }
        return builder.build()
    }

    /** Uploads a save into its own slot in RomM, so it sits beside the web player's saves instead of replacing them. */
    fun uploadSave(
        romId: Int,
        file: File,
        fileName: String,
    ) {
        val target =
            url("api/saves")
                .addQueryParameter("rom_id", romId.toString())
                .addQueryParameter("emulator", SAVE_EMULATOR)
                .addQueryParameter("slot", SAVE_SLOT)
                .addQueryParameter("autocleanup", "true")
                .addQueryParameter("autocleanup_limit", "5")
                .build()
        val body =
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("saveFile", fileName, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
        execute(authed(Request.Builder().url(target)).post(body).build()).close()
    }

    /** Addresses to try for an image stored on the RomM server: the .webp copy the web page uses, then the path as given. */
    fun imageCandidates(path: String?): List<String> {
        if (path.isNullOrBlank()) return emptyList()
        if (path.startsWith("http")) return listOf(path)
        val clean = session.serverUrl + "/" + path.substringBefore('?').trimStart('/')
        val webp = clean.replace(Regex("\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE), ".webp")
        return listOf(webp, clean).distinct()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        const val SAVE_SLOT = "RomM TV"
        const val SAVE_EMULATOR = "romm-tv"

        val REQUESTED_SCOPES =
            listOf(
                "me.read", "me.write",
                "roms.read", "roms.user.read", "roms.user.write",
                "platforms.read",
                "assets.read", "assets.write",
                "devices.read", "devices.write",
                "firmware.read",
                "collections.read",
            )

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }

        fun iso(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifBlank { null }
    }
}
