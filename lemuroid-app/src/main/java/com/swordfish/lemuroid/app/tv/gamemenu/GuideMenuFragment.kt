package com.swordfish.lemuroid.app.tv.gamemenu

import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.preference.Preference
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.tv.romm.GuidePanel

/**
 * The guide's buttons, in the game menu while the guide shows on the left. Back closes the guide and returns
 * to the game menu; "Back to game" closes everything.
 */
class GuideMenuFragment : LeanbackPreferenceFragmentCompat() {
    private val guide: GuidePanel? get() = (activity as? TVGameMenuActivity)?.guide
    private var statusPref: Preference? = null

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        screen.title = "Guide"
        val panel = guide
        val place = panel?.suggestions?.firstOrNull()

        fun item(
            key: String,
            title: String,
            summary: String?,
            icon: Int,
        ) = Preference(context).apply {
            this.key = key
            this.title = title
            this.summary = summary
            isIconSpaceReserved = true
            setIcon(icon)
            screen.addPreference(this)
        }

        statusPref =
            Preference(context).apply {
                key = "status"
                title = panel?.status?.ifBlank { null } ?: "Loading the guide…"
                isSelectable = false
                isIconSpaceReserved = true
                screen.addPreference(this)
            }
        item("back", "Back to game", null, R.drawable.ic_menu_restart)
        item(
            "jump",
            "Jump to where you are",
            if (place != null) "Y · $place" else "RetroAchievements isn't saying where you are in this game",
            R.drawable.ic_menu_guide,
        )
        item("next", "Next match", "X", R.drawable.ic_menu_fast_forward)
        item("top", "Back to top", null, R.drawable.ic_menu_load)
        item("find", "Find text…", null, R.drawable.ic_menu_image)
        item("scroll", "Scroll or pick links", "Left: into the guide · Up/Down scroll · L/R page · Right or B: back here", R.drawable.ic_menu_controls)
        item("other", "Choose another guide", null, R.drawable.ic_menu_load)
        preferenceScreen = screen
    }

    override fun onResume() {
        super.onResume()
        val panel = guide ?: return
        (activity as? TVGameMenuActivity)?.fitGuide()
        panel.show()
        panel.onStatus = { text -> statusPref?.title = text.ifBlank { "Guide" } }
    }

    override fun onDestroyView() {
        guide?.let {
            it.onStatus = {}
            it.hide()
        }
        super.onDestroyView()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val panel = guide ?: return super.onPreferenceTreeClick(preference)
        when (preference.key) {
            "back" -> activity?.finish()
            "jump" -> panel.jumpToSuggestion()
            "next" -> panel.nextMatch()
            "find" -> panel.askFind()
            "scroll" -> panel.focusGuide()
            "top" -> panel.toTop()
            "other" -> panel.otherGuides()
            else -> return super.onPreferenceTreeClick(preference)
        }
        return true
    }
}
