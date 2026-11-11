package com.swordfish.lemuroid.app.tv.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Opens the TV home however the app was started, so the phone screens never show up on a TV. */
class TVLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainTVActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
