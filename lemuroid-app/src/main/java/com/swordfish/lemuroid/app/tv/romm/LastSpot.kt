package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.graphics.Bitmap
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.io.File
import java.security.MessageDigest

/**
 * A picture of where you left off in each game, shown on the Continue playing card. Taken while you play (every
 * little while, and whenever the game menu opens, which is how you quit), so it matches the spot the game
 * resumes from. Kept per device: a save picked up from another device has no picture until you play it here.
 */
object LastSpot {
    private fun dir(context: Context) = File(context.applicationContext.filesDir, "last-spot").apply { mkdirs() }

    fun file(
        context: Context,
        game: Game,
    ): File {
        val key = MessageDigest.getInstance("SHA-1").digest(game.fileUri.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(dir(context), "$key.jpg")
    }

    private fun textFile(
        context: Context,
        game: Game,
    ) = File(file(context, game).path.removeSuffix(".jpg") + ".txt")

    /** What RetroAchievements said you were doing when you left off, or null if it said nothing. */
    fun text(
        context: Context,
        game: Game,
    ): String? = textFile(context, game).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    /** Saves (or, when blank, forgets) what RetroAchievements says you're doing. */
    fun saveText(
        context: Context,
        game: Game,
        text: String,
    ) {
        val f = textFile(context, game)
        if (text.isBlank()) f.delete() else f.writeText(text.trim())
    }

    fun save(
        context: Context,
        game: Game,
        bitmap: Bitmap,
    ) {
        val picture = trimBlackBorders(bitmap)
        val target = file(context, game)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        temp.renameTo(target)
    }

    /** The screen around the game picture is black; keep only the picture. */
    private fun trimBlackBorders(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return bitmap

        fun bright(
            x: Int,
            y: Int,
        ): Boolean {
            val c = bitmap.getPixel(x, y)
            return ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 36
        }

        fun columnHasPicture(x: Int) = (0 until h step 3).any { bright(x, it) }

        fun rowHasPicture(y: Int) = (0 until w step 3).any { bright(it, y) }
        val left = (0 until w).firstOrNull { columnHasPicture(it) } ?: return bitmap
        val right = (w - 1 downTo left).first { columnHasPicture(it) }
        val top = (0 until h).first { rowHasPicture(it) }
        val bottom = (h - 1 downTo top).first { rowHasPicture(it) }
        val cw = right - left + 1
        val ch = bottom - top + 1
        // A mostly dark scene could look like borders; only trim when a real picture is left
        if (cw < w / 3 || ch < h / 3) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, cw, ch)
    }
}
