package com.swordfish.lemuroid.lib.romm

import android.content.Context

/** How the last library sync with RomM went, so the app can show it and know when to sync again. */
object RommLibraryStatus {
    private const val PREFS = "romm_library"

    data class Status(
        val attemptAt: Long,
        val successAt: Long,
        val listed: Int,
        val total: Int,
        val error: String?,
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun started(context: Context) {
        prefs(context).edit().putLong("attempt", System.currentTimeMillis()).remove("error").commit()
    }

    fun progress(
        context: Context,
        listed: Int,
        total: Int,
    ) {
        prefs(context).edit().putInt("listed", listed).putInt("total", total).apply()
    }

    fun finished(
        context: Context,
        listed: Int,
        total: Int,
    ) {
        prefs(context).edit()
            .putLong("success", System.currentTimeMillis())
            .putInt("listed", listed)
            .putInt("total", total)
            .remove("error")
            .commit()
    }

    fun failed(
        context: Context,
        error: String,
    ) {
        prefs(context).edit().putString("error", error).commit()
    }

    fun read(context: Context): Status {
        val p = prefs(context)
        return Status(p.getLong("attempt", 0), p.getLong("success", 0), p.getInt("listed", 0), p.getInt("total", 0), p.getString("error", null))
    }
}
