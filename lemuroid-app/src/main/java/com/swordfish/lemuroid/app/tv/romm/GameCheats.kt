package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Cheats per game. Ready-made ones come from the libretro cheat library (the one RetroArch uses:
 * github.com/libretro/libretro-database, "cht" folder), found by matching the game's file name; you can add your
 * own Game Genie / Action Replay codes too. The ones switched on are applied when the game starts.
 * Everything is kept in small files, since games run in their own process.
 */
object GameCheats {
    data class Cheat(val description: String, val code: String, val custom: Boolean = false)

    private const val REPO = "libretro/libretro-database"
    private const val INDEX_DAYS = 14L
    private const val LIBRARY_DAYS = 14L

    /** The library's folder for each console. */
    fun folder(system: SystemID): String? =
        when (system) {
            SystemID.NES -> "Nintendo - Nintendo Entertainment System"
            SystemID.SNES -> "Nintendo - Super Nintendo Entertainment System"
            SystemID.GB -> "Nintendo - Game Boy"
            SystemID.GBC -> "Nintendo - Game Boy Color"
            SystemID.GBA -> "Nintendo - Game Boy Advance"
            SystemID.N64 -> "Nintendo - Nintendo 64"
            SystemID.NDS -> "Nintendo - Nintendo DS"
            SystemID.GENESIS -> "Sega - Mega Drive - Genesis"
            SystemID.SEGACD -> "Sega - Mega-CD - Sega CD"
            SystemID.SMS -> "Sega - Master System - Mark III"
            SystemID.GG -> "Sega - Game Gear"
            SystemID.PSX -> "Sony - PlayStation"
            SystemID.DREAMCAST -> "Sega - Dreamcast"
            SystemID.SATURN -> "Sega - Saturn"
            SystemID.PSP -> "Sony - PlayStation Portable"
            SystemID.PC_ENGINE -> "NEC - PC Engine - TurboGrafx 16"
            SystemID.LYNX -> "Atari - Lynx"
            SystemID.ATARI2600 -> "Atari - 2600"
            SystemID.ATARI7800 -> "Atari - 7800"
            SystemID.NGP -> "SNK - Neo Geo Pocket"
            SystemID.NGC -> "SNK - Neo Geo Pocket Color"
            SystemID.WS -> "Bandai - WonderSwan"
            SystemID.WSC -> "Bandai - WonderSwan Color"
            SystemID.FBNEO -> "FBNeo - Arcade Games"
            else -> null
        }

    private fun dir(
        context: Context,
        sub: String,
    ) = File(File(context.applicationContext.filesDir, "cheats"), sub).apply { mkdirs() }

    private fun key(game: Game): String =
        MessageDigest.getInstance("SHA-1").digest(game.fileUri.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    // ---- your choices

    private fun choicesFile(
        context: Context,
        game: Game,
    ) = File(dir(context, "on"), key(game) + ".json")

    private class Choices(val on: List<Cheat>, val custom: List<Cheat>)

    private fun readChoices(
        context: Context,
        game: Game,
    ): Choices =
        runCatching {
            val o = JSONObject(choicesFile(context, game).readText())
            fun list(name: String) =
                o.optJSONArray(name)?.let { a ->
                    (0 until a.length()).map { i ->
                        val c = a.getJSONObject(i)
                        Cheat(c.getString("desc"), c.getString("code"), c.optBoolean("custom"))
                    }
                } ?: emptyList()
            Choices(list("on"), list("custom"))
        }.getOrDefault(Choices(emptyList(), emptyList()))

    private fun writeChoices(
        context: Context,
        game: Game,
        choices: Choices,
    ) {
        fun array(list: List<Cheat>) =
            JSONArray().apply { list.forEach { put(JSONObject().put("desc", it.description).put("code", it.code).put("custom", it.custom)) } }
        val f = choicesFile(context, game)
        if (choices.on.isEmpty() && choices.custom.isEmpty()) {
            f.delete()
            return
        }
        f.writeText(JSONObject().put("on", array(choices.on)).put("custom", array(choices.custom)).toString())
    }

    /** The cheats switched on for this game (what gets applied when it starts). */
    fun enabled(
        context: Context,
        game: Game,
    ): List<Cheat> = readChoices(context, game).on

    fun customCheats(
        context: Context,
        game: Game,
    ): List<Cheat> = readChoices(context, game).custom

    fun isOn(
        context: Context,
        game: Game,
        cheat: Cheat,
    ) = readChoices(context, game).on.any { it.code == cheat.code && it.description == cheat.description }

    fun setOn(
        context: Context,
        game: Game,
        cheat: Cheat,
        on: Boolean,
    ) {
        val c = readChoices(context, game)
        val others = c.on.filterNot { it.code == cheat.code && it.description == cheat.description }
        writeChoices(context, game, Choices(if (on) others + cheat else others, c.custom))
    }

    fun allOff(
        context: Context,
        game: Game,
    ) {
        val c = readChoices(context, game)
        writeChoices(context, game, Choices(emptyList(), c.custom))
    }

    /** Adds your own code (switched on straight away). */
    fun addCustom(
        context: Context,
        game: Game,
        description: String,
        code: String,
    ): Cheat {
        val cheat = Cheat(description.trim().ifBlank { "My code" }, code.trim(), custom = true)
        val c = readChoices(context, game)
        writeChoices(context, game, Choices(c.on + cheat, c.custom + cheat))
        return cheat
    }

    fun removeCustom(
        context: Context,
        game: Game,
        cheat: Cheat,
    ) {
        val c = readChoices(context, game)
        writeChoices(
            context,
            game,
            Choices(
                c.on.filterNot { it.code == cheat.code && it.description == cheat.description },
                c.custom.filterNot { it.code == cheat.code && it.description == cheat.description },
            ),
        )
    }

    // ---- the library (blocking calls; run off the main thread)

    /** Ready-made cheats for this game, or an empty list if the library has none. */
    fun library(
        context: Context,
        game: Game,
    ): List<Cheat> {
        val cache = File(dir(context, "library"), key(game) + ".cht")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < LIBRARY_DAYS * 86_400_000L
        if (fresh) return parse(cache.readText())
        val system = SystemID.values().firstOrNull { it.dbname == game.systemId } ?: return emptyList()
        val folder = folder(system) ?: return emptyList()
        val text =
            runCatching {
                val files = index(context, folder)
                val name = bestMatch(files, game) ?: return@runCatching ""
                download(
                    "https://raw.githubusercontent.com/$REPO/master/cht/" + encode(folder) + "/" + encode(name),
                )
            }.onFailure { Timber.w(it, "Cheats: couldn't get the list for ${game.title}") }
                .getOrElse { return if (cache.exists()) parse(cache.readText()) else emptyList() }
        cache.writeText(text)
        return parse(text)
    }

    /** Every cheat file name in one console's folder (fetched once, refreshed every two weeks). */
    private fun index(
        context: Context,
        folder: String,
    ): List<String> {
        val cache = File(dir(context, "index"), folder.replace(Regex("[^A-Za-z0-9]+"), "_") + ".txt")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < INDEX_DAYS * 86_400_000L) {
            return cache.readLines().filter { it.isNotBlank() }
        }
        fun tree(sha: String) = JSONObject(download("https://api.github.com/repos/$REPO/git/trees/$sha")).getJSONArray("tree")
        fun shaOf(
            tree: JSONArray,
            path: String,
        ): String = (0 until tree.length()).map { tree.getJSONObject(it) }.first { it.getString("path") == path }.getString("sha")
        val chtSha = shaOf(tree("master"), "cht")
        val folderSha = shaOf(tree(chtSha), folder)
        val entries = tree(folderSha)
        val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }.filter { it.endsWith(".cht") }
        cache.writeText(names.joinToString("\n"))
        return names
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun base(name: String) = name.substringBeforeLast('.').substringBefore(" (").substringBefore(" [").trim()

    private fun tags(name: String) =
        Regex("\\(([^)]*)\\)").findAll(name).flatMap { m -> m.groupValues[1].split(',').map { it.trim().lowercase() } }.toSet()

    /** The library file for this game: same title, then the closest region and version. */
    private fun bestMatch(
        files: List<String>,
        game: Game,
    ): String? {
        val fileName = game.fileName
        val wanted = setOf(normalize(base(fileName)), normalize(base(game.title)))
        val candidates = files.filter { normalize(base(it)) in wanted }
        if (candidates.isEmpty()) return null
        val ourTags = tags(fileName)
        val ourStem = fileName.substringBeforeLast('.').lowercase()
        return candidates.maxByOrNull { f ->
            val t = tags(f)
            var score = 0
            if (f.substringBeforeLast('.').lowercase() == ourStem) score += 100
            score += 10 * t.count { it in ourTags }
            if (t.any { it.startsWith("beta") || it.startsWith("proto") || it == "sample" || it == "demo" }) score -= 20
            if ("usa" in t) score += 3
            if ("world" in t) score += 2
            score -= t.size
            score
        }
    }

    /** Reads a .cht file: cheatN_desc / cheatN_code pairs. */
    fun parse(text: String): List<Cheat> {
        if (text.isBlank()) return emptyList()
        val values = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) values[line.substring(0, i).trim()] = line.substring(i + 1).trim().trim('"')
        }
        val count = values["cheats"]?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { n ->
            val code = values["cheat${n}_code"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Cheat(values["cheat${n}_desc"]?.ifBlank { null } ?: "Cheat ${n + 1}", code)
        }
    }

    private fun encode(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", RetroAchievementsAccount.product)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code >= 400) throw IllegalStateException("HTTP $code")
            return connection.inputStream.use { it.reader().readText() }
        } finally {
            connection.disconnect()
        }
    }
}
