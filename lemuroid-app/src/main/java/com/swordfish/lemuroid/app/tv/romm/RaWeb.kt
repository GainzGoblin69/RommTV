package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * RetroAchievements' public web API, for the profile page: your stats, recently played games, recent unlocks,
 * awards, and each game's achievements, leaderboards, comments and supported files. It needs your "web API key"
 * (on retroachievements.org under Settings, Keys), which is separate from the sign-in used while playing.
 * Blocking calls: run them off the main thread.
 */
object RaWeb {
    private const val BASE = "https://retroachievements.org/API/"
    const val MEDIA = "https://media.retroachievements.org"

    private fun keyFile(context: Context) = File(context.applicationContext.filesDir, "ra-web-key.txt")

    fun apiKey(context: Context): String? = runCatching { keyFile(context).readText().trim() }.getOrNull()?.ifBlank { null }

    fun setApiKey(
        context: Context,
        key: String?,
    ) {
        if (key.isNullOrBlank()) keyFile(context).delete() else keyFile(context).writeText(key.trim())
    }

    class ApiException(message: String) : Exception(message)

    private fun get(
        key: String,
        endpoint: String,
        vararg params: Pair<String, Any>,
    ): String {
        val query = (listOf("y" to key) + params).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v.toString(), "UTF-8") }
        val connection = URL("$BASE$endpoint.php?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", RetroAchievementsAccount.product)
            val code = connection.responseCode
            if (code == 401 || code == 403) throw ApiException("RetroAchievements didn't accept the web API key")
            if (code >= 400) throw ApiException("RetroAchievements answered HTTP $code")
            return connection.inputStream.use { it.reader().readText() }
        } finally {
            connection.disconnect()
        }
    }

    // ---- data

    data class Profile(
        val user: String,
        val picUrl: String,
        val memberSince: String,
        val motto: String,
        val hardcorePoints: Int,
        val softcorePoints: Int,
        val truePoints: Int,
        val richPresence: String,
        val lastGameId: Int,
    ) {
        /** RetroAchievements' "retro ratio": weighted (true) points per hardcore point. */
        val retroRatio: Double get() = if (hardcorePoints > 0) truePoints.toDouble() / hardcorePoints else 0.0
    }

    data class PlayedGame(
        val id: Int,
        val title: String,
        val console: String,
        val iconUrl: String,
        val ingameUrl: String,
        val lastPlayed: String,
        val achieved: Int,
        val possible: Int,
    )

    data class Unlock(
        val title: String,
        val description: String,
        val points: Int,
        val badgeUrl: String,
        val gameTitle: String,
        val gameId: Int,
        val date: String,
        val hardcore: Boolean,
    )

    data class Award(
        val title: String,
        val console: String,
        val iconUrl: String,
        val kind: String,
        val date: String,
        val gameId: Int,
    )

    data class Awards(
        val total: Int,
        val mastered: Int,
        val completed: Int,
        val beaten: Int,
        val list: List<Award>,
    )

    data class Achievement(
        val title: String,
        val description: String,
        val points: Int,
        val trueRatio: Int,
        val badgeUrl: String,
        val lockedBadgeUrl: String,
        val awarded: Int,
        val awardedHardcore: Int,
        val earned: String?,
        val order: Int,
    )

    data class GameProgress(
        val id: Int,
        val title: String,
        val console: String,
        val iconUrl: String,
        val ingameUrl: String,
        val players: Int,
        val achievements: List<Achievement>,
    ) {
        val earned get() = achievements.count { it.earned != null }
        val points get() = achievements.sumOf { it.points }
        val earnedPoints get() = achievements.filter { it.earned != null }.sumOf { it.points }
    }

    data class Leaderboard(val title: String, val description: String, val format: String, val lowerIsBetter: Boolean, val top: String?)

    data class Comment(val user: String, val date: String, val text: String)

    data class FileHash(val name: String, val md5: String, val labels: List<String>)

    // ---- calls

    private fun media(path: String?) = if (path.isNullOrBlank()) "" else if (path.startsWith("http")) path else MEDIA + path

    fun profile(
        key: String,
        user: String,
    ): Profile {
        val o = JSONObject(get(key, "API_GetUserProfile", "u" to user))
        return Profile(
            user = o.optString("User", user),
            picUrl = media(o.optString("UserPic").ifBlank { "/UserPic/$user.png" }),
            memberSince = o.optString("MemberSince"),
            motto = o.optString("Motto"),
            hardcorePoints = o.optInt("TotalPoints"),
            softcorePoints = o.optInt("TotalSoftcorePoints"),
            truePoints = o.optInt("TotalTruePoints"),
            richPresence = o.optString("RichPresenceMsg"),
            lastGameId = o.optInt("LastGameID"),
        )
    }

    fun recentlyPlayed(
        key: String,
        user: String,
    ): List<PlayedGame> {
        val a = JSONArray(get(key, "API_GetUserRecentlyPlayedGames", "u" to user, "c" to 50))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            PlayedGame(
                id = it.optInt("GameID"),
                title = it.optString("Title"),
                console = it.optString("ConsoleName"),
                iconUrl = media(it.optString("ImageIcon")),
                ingameUrl = media(it.optString("ImageIngame")),
                lastPlayed = it.optString("LastPlayed"),
                achieved = maxOf(it.optInt("NumAchieved"), it.optInt("NumAchievedHardcore")),
                possible = it.optInt("NumPossibleAchievements", it.optInt("AchievementsTotal")),
            )
        }
    }

    /** Unlocks from the last 30 days, newest first. */
    fun recentUnlocks(
        key: String,
        user: String,
    ): List<Unlock> {
        val a = JSONArray(get(key, "API_GetUserRecentAchievements", "u" to user, "m" to 30 * 24 * 60))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Unlock(
                title = it.optString("Title"),
                description = it.optString("Description"),
                points = it.optInt("Points"),
                badgeUrl = media(it.optString("BadgeURL").ifBlank { "/Badge/${it.optString("BadgeName")}.png" }),
                gameTitle = it.optString("GameTitle"),
                gameId = it.optInt("GameID"),
                date = it.optString("Date"),
                hardcore = it.optInt("HardcoreMode") == 1 || it.optBoolean("HardcoreMode"),
            )
        }.sortedByDescending { it.date }
    }

    fun awards(
        key: String,
        user: String,
    ): Awards {
        val o = JSONObject(get(key, "API_GetUserAwards", "u" to user))
        val a = o.optJSONArray("VisibleUserAwards") ?: JSONArray()
        val list =
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                val type = it.optString("AwardType")
                val hardcore = it.optInt("AwardDataExtra") == 1
                Award(
                    title = it.optString("Title"),
                    console = it.optString("ConsoleName"),
                    iconUrl = media(it.optString("ImageIcon")),
                    kind =
                        when {
                            type.contains("Mastery", true) -> if (hardcore) "Mastered" else "Completed"
                            type.contains("Beaten", true) -> if (hardcore) "Beaten" else "Beaten (softcore)"
                            else -> type
                        },
                    date = it.optString("AwardedAt"),
                    gameId = it.optInt("AwardData"),
                )
            }.sortedByDescending { it.date }
        return Awards(
            total = o.optInt("TotalAwardsCount", list.size),
            mastered = o.optInt("MasteryAwardsCount"),
            completed = o.optInt("CompletionAwardsCount"),
            beaten = o.optInt("BeatenHardcoreAwardsCount") + o.optInt("BeatenSoftcoreAwardsCount"),
            list = list,
        )
    }

    fun game(
        key: String,
        user: String,
        gameId: Int,
    ): GameProgress {
        val o = JSONObject(get(key, "API_GetGameInfoAndUserProgress", "u" to user, "g" to gameId))
        val raw = o.opt("Achievements")
        val items =
            when (raw) {
                is JSONObject -> raw.keys().asSequence().map { raw.getJSONObject(it) }.toList()
                is JSONArray -> (0 until raw.length()).map { raw.getJSONObject(it) }
                else -> emptyList()
            }
        val achievements =
            items.map {
                val badge = it.optString("BadgeName")
                val earned = it.optString("DateEarnedHardcore").ifBlank { it.optString("DateEarned") }.ifBlank { null }
                Achievement(
                    title = it.optString("Title"),
                    description = it.optString("Description"),
                    points = it.optInt("Points"),
                    trueRatio = it.optInt("TrueRatio"),
                    badgeUrl = "$MEDIA/Badge/$badge.png",
                    lockedBadgeUrl = "$MEDIA/Badge/${badge}_lock.png",
                    awarded = it.optInt("NumAwarded"),
                    awardedHardcore = it.optInt("NumAwardedHardcore"),
                    earned = earned,
                    order = it.optInt("DisplayOrder"),
                )
            }.sortedBy { it.order }
        return GameProgress(
            id = o.optInt("ID", gameId),
            title = o.optString("Title"),
            console = o.optString("ConsoleName"),
            iconUrl = media(o.optString("ImageIcon")),
            ingameUrl = media(o.optString("ImageIngame")),
            players = o.optInt("NumDistinctPlayers", o.optInt("NumDistinctPlayersCasual")),
            achievements = achievements,
        )
    }

    fun leaderboards(
        key: String,
        gameId: Int,
    ): List<Leaderboard> {
        val o = JSONObject(get(key, "API_GetGameLeaderboards", "i" to gameId, "c" to 200))
        val a = o.optJSONArray("Results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            val top = it.optJSONObject("TopEntry")
            Leaderboard(
                title = it.optString("Title"),
                description = it.optString("Description"),
                format = it.optString("Format"),
                lowerIsBetter = it.optBoolean("RankAsc"),
                top = top?.let { t -> "${t.optString("User")}: ${t.optString("FormattedScore", t.optString("Score"))}" },
            )
        }
    }

    fun comments(
        key: String,
        gameId: Int,
    ): List<Comment> {
        val o = JSONObject(get(key, "API_GetComments", "t" to 1, "i" to gameId, "c" to 100, "sort" to "-submitted"))
        val a = o.optJSONArray("Results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Comment(it.optString("User"), it.optString("Submitted"), it.optString("CommentText"))
        }.sortedByDescending { it.date }
    }

    fun hashes(
        key: String,
        gameId: Int,
    ): List<FileHash> {
        val o = JSONObject(get(key, "API_GetGameHashes", "i" to gameId))
        val a = o.optJSONArray("Results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            val labels = it.optJSONArray("Labels")
            FileHash(
                it.optString("Name"),
                it.optString("MD5"),
                labels?.let { l -> (0 until l.length()).map { i -> l.getString(i) } }.orEmpty(),
            )
        }
    }

    /** "2024-05-20 12:34:56" or "2024-05-20T12:34:56Z" → "May 20, 2024". */
    fun prettyDate(value: String): String {
        if (value.length < 10) return value
        return runCatching {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(input.parse(value.substring(0, 10))!!)
        }.getOrDefault(value)
    }
}
