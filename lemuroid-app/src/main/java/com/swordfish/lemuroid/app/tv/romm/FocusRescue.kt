package com.swordfish.lemuroid.app.tv.romm

import android.view.KeyEvent

/**
 * Touching the screen (on a phone, scrolling with a finger) can leave nothing selected; a controller then has
 * nothing to move from and seems dead. Screens track whether anything on them is selected, and when a
 * controller's direction or A press arrives with nothing selected they select a sensible starting point instead.
 */
object FocusRescue {
    fun isNavigation(event: KeyEvent) =
        event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in
            setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
            )
}
