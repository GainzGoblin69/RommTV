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

    @Volatile
    private var powerful = false

    fun init(context: Context) {
        val app = context.applicationContext
        val uiMode = (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        val pm = app.packageManager
        tv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        tablet = app.resources.configuration.smallestScreenWidthDp >= 600
        // Enough for GameCube, Wii and 3DS: a 64-bit chip and plenty of memory (phones yes, these TVs no)
        val memory =
            android.app.ActivityManager.MemoryInfo().also {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(it)
            }.totalMem
        powerful = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && memory >= 5_500_000_000L
    }

    /** Whether this device can run the heaviest consoles (GameCube, Wii, 3DS). */
    val isPowerful: Boolean get() = powerful

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
