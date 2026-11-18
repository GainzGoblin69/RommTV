package com.swordfish.lemuroid.app.shared.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import java.io.File

/**
 * Xbox or Nintendo button style, per controller. Nintendo style swaps A with B and X with Y, so the button in
 * the same place does the same thing as on a Nintendo pad. Remembered per controller, in a small file (games
 * and their menu run in their own process).
 */
object ButtonStyle {
    private var swapped: MutableSet<String>? = null

    /** The controller that last opened the game menu (whoever opens it can switch their own controller). */
    @Volatile
    var menuOpener: InputDevice? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, "nintendo-buttons.txt")

    private fun load(context: Context): MutableSet<String> =
        swapped ?: runCatching { file(context).readLines().filter { it.isNotBlank() }.toMutableSet() }
            .getOrDefault(mutableSetOf())
            .also { swapped = it }

    fun isNintendo(
        context: Context,
        device: InputDevice?,
    ) = device != null && device.descriptor in load(context)

    fun setNintendo(
        context: Context,
        device: InputDevice,
        on: Boolean,
    ) {
        val set = load(context)
        if (on) set += device.descriptor else set -= device.descriptor
        runCatching { file(context).writeText(set.joinToString("\n")) }
    }

    /** The button the game should see for [keyCode] from [device]. */
    fun apply(
        context: Context,
        device: InputDevice?,
        keyCode: Int,
    ): Int {
        if (!isNintendo(context, device)) return keyCode
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    }
}
