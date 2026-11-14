package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import com.swordfish.lemuroid.R

/**
 * Menu sounds: a soft tick when the selection moves, a bright two-note click when you press something, and a
 * lower two-note tone for back. Original sounds made for RomM TV. Only for remote and controller use: a sound
 * plays only right after a button press, so screens opening (or touch on a phone) stay quiet.
 */
object UiSounds {
    private const val PREFS = "romm_ui"
    private const val KEY_ENABLED = "menu_sounds"

    private var pool: SoundPool? = null
    private var moveId = 0
    private var selectId = 0
    private var backId = 0

    @Volatile
    private var lastNavKey = 0L
    private var lastMove = 0L

    @Volatile
    private var enabled = true

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        val p =
            SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).build()
        moveId = p.load(app, R.raw.ui_move, 1)
        selectId = p.load(app, R.raw.ui_select, 1)
        backId = p.load(app, R.raw.ui_back, 1)
        pool = p
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        value: Boolean,
    ) {
        enabled = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun play(
        id: Int,
        volume: Float,
    ) {
        if (!enabled || id == 0) return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    /** Call at the start of an activity's dispatchKeyEvent: plays select and back, and notes D-pad presses. */
    fun onKey(
        context: Context,
        event: KeyEvent,
    ) {
        init(context)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (event.action == KeyEvent.ACTION_DOWN) lastNavKey = SystemClock.uptimeMillis()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) play(selectId, 0.8f)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) play(backId, 0.75f)
        }
    }

    /** The selection moved. Plays only if it moved because of a D-pad press (and not more than ~25 times a second). */
    fun moved() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNavKey > 350 || now - lastMove < 40) return
        lastMove = now
        play(moveId, 0.7f)
    }
}

/**
 * Like clickable, plus a short tap you can feel when pressed on a phone or tablet
 * (follows the phone's "touch feedback" setting; TVs have nothing to vibrate).
 */
@Suppress("DEPRECATION")
fun Modifier.tapClickable(onClick: () -> Unit): Modifier =
    composed {
        val view = androidx.compose.ui.platform.LocalView.current
        clickable {
            if (!com.swordfish.lemuroid.lib.romm.Device.isTv(view.context)) {
                view.performHapticFeedback(
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                )
            }
            onClick()
        }
    }

/** Ticks when this item gets selected with the D-pad. */
fun Modifier.focusSound(): Modifier = onFocusChanged { if (it.isFocused) UiSounds.moved() }
