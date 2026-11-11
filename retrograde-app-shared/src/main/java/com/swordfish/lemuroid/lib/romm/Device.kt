package com.swordfish.lemuroid.lib.romm

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** What the app is running on: a TV (remote and controllers) or a phone/tablet (touch). */
object Device {
    @Volatile
    private var tv: Boolean? = null

    @Volatile
    private var tablet = false

    fun init(context: Context) {
        val app = context.applicationContext
        val uiMode = (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        val pm = app.packageManager
        tv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        tablet = app.resources.configuration.smallestScreenWidthDp >= 600
    }

    fun isTv(context: Context): Boolean {
        if (tv == null) init(context)
        return tv == true
    }

    /** "TV", "phone" or "tablet", for messages like "Remove from this TV". */
    val word: String
        get() =
            when {
                tv != false -> "TV"
                tablet -> "tablet"
                else -> "phone"
            }
}
