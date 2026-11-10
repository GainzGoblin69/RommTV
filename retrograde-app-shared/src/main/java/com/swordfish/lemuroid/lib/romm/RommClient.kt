package com.swordfish.lemuroid.lib.romm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
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
    val statesCount: Int,
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
    fun roms(
        offset: Int,
        limit: Int,
    ): Pair<List<RommRom>, Int> {
        val url =
            url("api/roms")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("with_files", "true")
                .addQueryParameter("order_by", "name")
                .addQueryParameter("order_dir", "asc")
                .build()
        val json = JSONObject(getJson(url))
        val items = json.getJSONArray("items")
        val roms = (0 until items.length()).mapNotNull { parseRom(items.getJSONObject(it)) }
        return roms to json.optInt("total", roms.size)
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
            statesCount = r.optJSONArray("user_states")?.length() ?: 0,
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

    fun downloadSave(
        saveId: Int,
        dest: File,
    ) = download(url("api/saves").addPathSegment(saveId.toString()).addPathSegment("content").build(), dest)

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

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).ifBlank { null }
    }
}
