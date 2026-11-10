package com.swordfish.lemuroid.app.shared.input

import android.content.Context
import android.view.KeyEvent
import com.swordfish.lemuroid.lib.library.SystemID
import org.json.JSONObject
import java.io.File

/**
 * Button layouts per console. Each console can have its own: which controller button presses which of the
 * console's buttons. A console with no custom layout uses the controller's normal mapping (buttons by position:
 * the bottom face button is the console's bottom button, and so on).
 *
 * Stored as small files rather than preferences because the game runs in its own process.
 */
object ConsoleControls {
    /** A controller button (Android key code) and where it sits. */
    data class PadButton(val keyCode: Int, val name: String)

    /** One of the console's buttons: the RetroPad key the emulator receives, and the console's own name for it. */
    data class ConsoleButton(val retroKey: Int, val label: String)

    val PAD_BUTTONS =
        listOf(
            PadButton(KeyEvent.KEYCODE_BUTTON_A, "A / ✕ (bottom)"),
            PadButton(KeyEvent.KEYCODE_BUTTON_B, "B / ○ (right)"),
            PadButton(KeyEvent.KEYCODE_BUTTON_X, "X / □ (left)"),
            PadButton(KeyEvent.KEYCODE_BUTTON_Y, "Y / △ (top)"),
            PadButton(KeyEvent.KEYCODE_BUTTON_L1, "LB / L1"),
            PadButton(KeyEvent.KEYCODE_BUTTON_R1, "RB / R1"),
            PadButton(KeyEvent.KEYCODE_BUTTON_L2, "LT / L2"),
            PadButton(KeyEvent.KEYCODE_BUTTON_R2, "RT / R2"),
            PadButton(KeyEvent.KEYCODE_BUTTON_THUMBL, "Left stick click"),
            PadButton(KeyEvent.KEYCODE_BUTTON_THUMBR, "Right stick click"),
            PadButton(KeyEvent.KEYCODE_BUTTON_SELECT, "View / Select / Share"),
            PadButton(KeyEvent.KEYCODE_BUTTON_START, "Menu / Start / Options"),
        )

    private val REMAPPABLE = PAD_BUTTONS.map { it.keyCode }.toSet()

    fun canCapture(keyCode: Int) = keyCode in REMAPPABLE

    fun padName(keyCode: Int): String = PAD_BUTTONS.firstOrNull { it.keyCode == keyCode }?.name ?: "Not set"

    /** The normal mapping: buttons by position (Android's BUTTON_A is the bottom button, the RetroPad's bottom is B). */
    fun defaultLayout(): Map<Int, Int> =
        REMAPPABLE.associateWith { key ->
            when (key) {
                KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
                KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
                else -> key
            }
        }

    private const val B = KeyEvent.KEYCODE_BUTTON_B
    private const val A = KeyEvent.KEYCODE_BUTTON_A
    private const val Y = KeyEvent.KEYCODE_BUTTON_Y
    private const val X = KeyEvent.KEYCODE_BUTTON_X
    private const val L = KeyEvent.KEYCODE_BUTTON_L1
    private const val R = KeyEvent.KEYCODE_BUTTON_R1
    private const val L2 = KeyEvent.KEYCODE_BUTTON_L2
    private const val R2 = KeyEvent.KEYCODE_BUTTON_R2
    private const val L3 = KeyEvent.KEYCODE_BUTTON_THUMBL
    private const val R3 = KeyEvent.KEYCODE_BUTTON_THUMBR
    private const val SELECT = KeyEvent.KEYCODE_BUTTON_SELECT
    private const val START = KeyEvent.KEYCODE_BUTTON_START

    private fun buttons(vararg pairs: Pair<Int, String>) = pairs.map { ConsoleButton(it.first, it.second) }

    /** The console's buttons, named the way that console names them. */
    fun consoleButtons(system: SystemID): List<ConsoleButton> =
        when (system) {
            SystemID.NES -> buttons(A to "A", B to "B", SELECT to "Select", START to "Start")
            SystemID.SNES ->
                buttons(A to "A", B to "B", X to "X", Y to "Y", L to "L", R to "R", SELECT to "Select", START to "Start")
            SystemID.GB, SystemID.GBC -> buttons(A to "A", B to "B", SELECT to "Select", START to "Start")
            SystemID.GBA -> buttons(A to "A", B to "B", L to "L", R to "R", SELECT to "Select", START to "Start")
            SystemID.NDS ->
                buttons(A to "A", B to "B", X to "X", Y to "Y", L to "L", R to "R", SELECT to "Select", START to "Start")
            SystemID.N64 ->
                buttons(B to "A", Y to "B", L2 to "Z", L to "L", R to "R", START to "Start")
            SystemID.GENESIS, SystemID.SEGACD ->
                buttons(Y to "A", B to "B", A to "C", L to "X", X to "Y", R to "Z", START to "Start", SELECT to "Mode")
            SystemID.SMS, SystemID.GG -> buttons(B to "1", A to "2", START to "Start / Pause")
            SystemID.PSX ->
                buttons(
                    B to "✕ Cross", A to "○ Circle", Y to "□ Square", X to "△ Triangle",
                    L to "L1", R to "R1", L2 to "L2", R2 to "R2", L3 to "L3", R3 to "R3",
                    SELECT to "Select", START to "Start",
                )
            SystemID.PSP ->
                buttons(
                    B to "✕ Cross", A to "○ Circle", Y to "□ Square", X to "△ Triangle",
                    L to "L", R to "R", SELECT to "Select", START to "Start",
                )
            SystemID.PC_ENGINE -> buttons(A to "I", B to "II", SELECT to "Select", START to "Run")
            SystemID.ATARI2600 -> buttons(B to "Fire", SELECT to "Select", START to "Reset")
            SystemID.ATARI7800 -> buttons(B to "Left fire", A to "Right fire", SELECT to "Select", START to "Pause")
            SystemID.LYNX -> buttons(A to "A", B to "B", L to "Option 1", R to "Option 2", START to "Pause")
            SystemID.NGP, SystemID.NGC -> buttons(B to "A", A to "B", START to "Option")
            SystemID.WS, SystemID.WSC -> buttons(A to "A", B to "B", START to "Start")
            SystemID.FBNEO, SystemID.MAME2003PLUS ->
                buttons(
                    B to "Button 1", A to "Button 2", Y to "Button 3", X to "Button 4",
                    L to "Button 5", R to "Button 6", SELECT to "Coin", START to "Start",
                )
            else ->
                buttons(A to "A", B to "B", X to "X", Y to "Y", L to "L", R to "R", SELECT to "Select", START to "Start")
        }

    private fun file(
        context: Context,
        system: SystemID,
    ) = File(File(context.applicationContext.filesDir, "console-controls"), "${system.dbname}.json")

    fun isCustom(
        context: Context,
        system: SystemID,
    ) = file(context, system).exists()

    /** The controller button → console button layout, or null when the console uses the normal mapping. */
    fun load(
        context: Context,
        system: SystemID,
    ): Map<Int, Int>? =
        runCatching {
            val f = file(context, system)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            o.keys().asSequence().associate { it.toInt() to o.getInt(it) }
        }.getOrNull()

    fun layout(
        context: Context,
        system: SystemID,
    ): Map<Int, Int> = load(context, system) ?: defaultLayout()

    /**
     * Puts [consoleKey] on [padKey]. Whatever [padKey] did before moves to the controller button that had
     * [consoleKey], so every button stays reachable.
     */
    fun assign(
        context: Context,
        system: SystemID,
        padKey: Int,
        consoleKey: Int,
    ): Map<Int, Int> {
        val layout = layout(context, system).toMutableMap()
        val previousOnPad = layout[padKey]
        val holder = layout.entries.firstOrNull { it.value == consoleKey && it.key != padKey }?.key
        layout[padKey] = consoleKey
        if (holder != null && previousOnPad != null) layout[holder] = previousOnPad
        save(context, system, layout)
        return layout
    }

    fun reset(
        context: Context,
        system: SystemID,
    ) {
        file(context, system).delete()
    }

    private fun save(
        context: Context,
        system: SystemID,
        layout: Map<Int, Int>,
    ) {
        val f = file(context, system)
        f.parentFile?.mkdirs()
        if (layout == defaultLayout()) {
            f.delete()
            return
        }
        val o = JSONObject()
        layout.forEach { (k, v) -> o.put(k.toString(), v) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }
}
