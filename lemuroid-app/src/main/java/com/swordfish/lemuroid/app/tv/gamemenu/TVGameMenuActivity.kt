package com.swordfish.lemuroid.app.tv.gamemenu

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.tv.shared.TVBaseSettingsActivity
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import java.security.InvalidParameterException
import javax.inject.Inject

class TVGameMenuActivity : TVBaseSettingsActivity() {
    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The paused game stays at full brightness behind the menu, so screen filters can be judged from here
        window.setDimAmount(0f)
        if (savedInstanceState == null) {
            val game =
                intent.extras?.getSerializable(GameMenuContract.EXTRA_GAME) as Game?
                    ?: throw InvalidParameterException("Missing EXTRA_GAME")

            val core =
                intent.extras?.getSerializable(
                    GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG,
                ) as SystemCoreConfig?
                    ?: throw InvalidParameterException("Missing EXTRA_SYSTEM_CORE_CONFIG")

            val options =
                intent.extras?.getSerializable(
                    GameMenuContract.EXTRA_CORE_OPTIONS,
                ) as Array<LemuroidCoreOption>?
                    ?: throw InvalidParameterException("Missing EXTRA_CORE_OPTIONS")

            val advancedOptions =
                intent.extras?.getSerializable(
                    GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS,
                ) as Array<LemuroidCoreOption>?
                    ?: throw InvalidParameterException("Missing EXTRA_ADVANCED_CORE_OPTIONS")

            val numDisks =
                intent.extras?.getInt(GameMenuContract.EXTRA_DISKS)
                    ?: throw InvalidParameterException("Missing EXTRA_DISKS")

            val currentDisk =
                intent.extras?.getInt(GameMenuContract.EXTRA_CURRENT_DISK)
                    ?: throw InvalidParameterException("Missing EXTRA_CURRENT_DISK")

            val audioEnabled =
                intent.extras?.getBoolean(GameMenuContract.EXTRA_AUDIO_ENABLED)
                    ?: throw InvalidParameterException("Missing EXTRA_AUDIO_ENABLED")

            val fastForwardEnabled =
                intent.extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD)
                    ?: throw InvalidParameterException("Missing EXTRA_FAST_FORWARD")

            val fastForwardSupported =
                intent.extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED)
                    ?: throw InvalidParameterException("Missing EXTRA_FAST_FORWARD_SUPPORTED")

            val fragment =
                TVGameMenuFragmentWrapper(
                    statesManager,
                    statesPreviewManager,
                    inputDeviceManager,
                    game,
                    core,
                    options,
                    advancedOptions,
                    numDisks,
                    currentDisk,
                    audioEnabled,
                    fastForwardEnabled,
                    fastForwardSupported,
                )
            supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment)
                .commit()
        }
    }

    /** The guide, once opened: it fills the left of the screen beside this menu. */
    var guide: com.swordfish.lemuroid.app.tv.romm.GuidePanel? = null
        private set

    fun openGuide(game: Game): com.swordfish.lemuroid.app.tv.romm.GuidePanel {
        guide?.let { return it }
        val panel = com.swordfish.lemuroid.app.tv.romm.GuidePanel(this, game.title, game.fileUri, game.systemId)
        addContentView(
            panel.frame,
            android.widget.FrameLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.START),
        )
        guide = panel
        return panel
    }

    /** Sizes the guide to the space left of the menu panel. */
    fun fitGuide() {
        val panel = guide ?: return
        val decor = window.decorView
        decor.post {
            val id = resources.getIdentifier("settings_preference_fragment_container", "id", packageName)
            val pane = if (id != 0) findViewById<android.view.View>(id) else null
            val left =
                if (pane != null && pane.width > 0) {
                    val at = IntArray(2)
                    pane.getLocationInWindow(at)
                    at[0]
                } else {
                    val dim = resources.getIdentifier("lb_settings_pane_width", "dimen", packageName)
                    decor.width - (if (dim != 0) resources.getDimensionPixelSize(dim) else (360 * resources.displayMetrics.density).toInt())
                }
            panel.frame.layoutParams = panel.frame.layoutParams.apply { width = left.coerceAtLeast(0) }
        }
    }

    private fun menuList(): android.view.View? {
        val id = resources.getIdentifier("settings_preference_fragment_container", "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    override fun onPause() {
        super.onPause()
        guide?.takeIf { it.isOpen }?.rememberPlace()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val panel = guide?.takeIf { it.isOpen } ?: return super.dispatchKeyEvent(event)
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        val inGuide = panel.web.hasFocus()
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> { if (down) panel.pageUp(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> { if (down) panel.pageDown(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> { if (down) panel.scrollBy(-panel.web.height / 5); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> { if (down) panel.scrollBy(panel.web.height / 5); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> { if (down && event.repeatCount == 0) panel.jumpToSuggestion(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_X -> { if (down) panel.nextMatch(); return true }
            android.view.KeyEvent.KEYCODE_BUTTON_B, android.view.KeyEvent.KEYCODE_BACK ->
                // In the guide, B goes back to its buttons; from the buttons it closes the guide as usual
                if (inGuide) {
                    if (!down) menuList()?.requestFocus()
                    return true
                }
            android.view.KeyEvent.KEYCODE_BUTTON_A ->
                if (inGuide) {
                    return super.dispatchKeyEvent(
                        android.view.KeyEvent(event.downTime, event.eventTime, event.action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount),
                    )
                }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                // From the buttons, Left reaches the guide (to scroll it or pick a link)
                if (!inGuide && down) {
                    panel.focusGuide()
                    return true
                }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    class TVGameMenuFragmentWrapper(
        private val statesManager: StatesManager,
        private val statesPreviewManager: StatesPreviewManager,
        private val inputDeviceManager: InputDeviceManager,
        private val game: Game,
        private val systemCoreConfig: SystemCoreConfig,
        private val coreOptions: Array<LemuroidCoreOption>,
        private val advancedCoreOptions: Array<LemuroidCoreOption>,
        private val numDisks: Int,
        private val currentDisk: Int,
        private val audioEnabled: Boolean,
        private val fastForwardEnabled: Boolean,
        private val fastForwardSupported: Boolean,
    ) : BaseSettingsFragmentWrapper() {
        override fun createFragment(): Fragment {
            return TVGameMenuFragment(
                statesManager,
                statesPreviewManager,
                inputDeviceManager,
                game,
                systemCoreConfig,
                coreOptions,
                advancedCoreOptions,
                numDisks,
                currentDisk,
                audioEnabled,
                fastForwardEnabled,
                fastForwardSupported,
            )
        }
    }
}
