package com.swordfish.lemuroid.app.shared.game

/**
 * Lets the game menu show a screen filter on the paused game behind it the moment it's picked.
 * The running game sets [apply]; the menu (same process) calls it with the filter's value ("crt", "lcd"...).
 */
object ScreenFilterPreview {
    @Volatile
    var apply: ((String) -> Unit)? = null
}
