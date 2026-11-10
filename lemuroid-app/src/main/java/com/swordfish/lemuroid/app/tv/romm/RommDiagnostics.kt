package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommSession
import okhttp3.Request

/**
 * A plain-text report of the connection to RomM, shown from the RomM account tile:
 * who the TV is signed in as, and what the server sends back for one game's cover art.
 */
object RommDiagnostics {
    fun report(context: Context): String {
        val session = RommSession(context)
        val client = RommClient(session)
        val lines = mutableListOf<String>()
        lines += "App build ${BuildConfig.VERSION_CODE}"
        lines += "Server: ${session.serverUrl}"
        lines += "Signed in: ${if (session.isSignedIn) "yes" else "no"}"
        if (!session.isSignedIn) return lines.joinToString("\n")

        val roms =
            runCatching { client.roms(0, 50).first }
                .getOrElse {
                    lines += "Couldn't list games: ${it.message}"
                    return lines.joinToString("\n")
                }
        val withCover = roms.count { client.coverUrl(it) != null }
        lines += "First ${roms.size} games: $withCover have cover art in RomM"

        val sample = roms.firstOrNull { client.coverUrl(it) != null }
        if (sample == null) {
            lines += "No cover paths came back from RomM for these games."
            return lines.joinToString("\n")
        }
        lines += ""
        lines += "Test game: ${sample.title}"
        lines += "Cover path from RomM: ${sample.coverPath ?: "none"}"
        lines += "Remote cover: ${sample.coverUrl ?: "none"}"
        val url = client.coverUrl(sample)!!
        lines += "App loads: $url"
        lines += "With sign-in: ${fetch(url, session.token)}"
        lines += "Without sign-in: ${fetch(url, null)}"
        return lines.joinToString("\n")
    }

    private fun fetch(
        url: String,
        token: String?,
    ): String =
        runCatching {
            val request =
                Request.Builder().url(url).apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
            RommClient.http.newCall(request).execute().use { response ->
                val type = response.header("Content-Type") ?: "?"
                val size = response.body?.bytes()?.size ?: 0
                val redirected = if (response.request.url.toString() != url) " (ended at ${response.request.url})" else ""
                "HTTP ${response.code}, $type, $size bytes$redirected"
            }
        }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
}
