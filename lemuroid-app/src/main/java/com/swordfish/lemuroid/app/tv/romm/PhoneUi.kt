package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.lemuroid.lib.romm.Device

/**
 * Makes the Big Picture screens behave on a phone or tablet: full screen (swipe from an edge to see the
 * system bars), and kept clear of the camera cutout. Does nothing on a TV.
 */
object PhoneUi {
    fun apply(activity: Activity) {
        if (Device.isTv(activity)) return
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val content = activity.findViewById<android.view.View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(cutout.left, cutout.top, cutout.right, maxOf(cutout.bottom, keyboard.bottom))
            insets
        }
    }
}
