package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.text.Normalizer

/**
 * Searching the library: every word typed has to appear in the title (in any order, ignoring case, accents
 * and punctuation, so "pokemon red" finds "Pokémon - Red Version"). Best matches first: titles that start
 * with what you typed, then titles with a word starting with it, then the rest, A to Z.
 */
object GameSearch {
    private const val PREFS = "romm_search"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 8

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun search(
        games: List<Game>,
        query: String,
    ): List<Game> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        return games
            .mapNotNull { game ->
                val title = normalize(game.title)
                val padded = " $title"
                if (!words.all { title.contains(it) }) return@mapNotNull null
                val rank =
                    when {
                        title == q -> 0
                        title.startsWith(q) -> 1
                        padded.contains(" $q") -> 2
                        words.all { padded.contains(" $it") } -> 3
                        else -> 4
                    }
                Triple(game, rank, title)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
    }

    fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "")!!
            .split('\n')
            .filter { it.isNotBlank() }

    fun remember(
        context: Context,
        query: String,
    ): List<String> {
        val q = query.trim()
        val list = (listOf(q) + recent(context).filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        return list
    }
}
