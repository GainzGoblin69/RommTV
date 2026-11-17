package com.swordfish.lemuroid.app.tv.gamemenu

import android.os.Bundle
import android.view.View
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOptionsPreferenceHelper
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.gamemenu.GameMenuHelper
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.coroutines.safeCollect
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager

class TVGameMenuFragment(
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
) : LeanbackPreferenceFragmentCompat() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceManager.preferenceDataStore =
            SharedPreferencesHelper.getSharedPreferencesDataStore(requireContext())
        setPreferencesFromResource(R.xml.tv_game_settings, rootKey)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        GameMenuHelper.setupAudioOption(preferenceScreen, audioEnabled)
        GameMenuHelper.setupFastForwardOption(preferenceScreen, fastForwardEnabled, fastForwardSupported)
        GameMenuHelper.setupSaveOption(preferenceScreen, systemCoreConfig)

        // Picking a filter here takes effect as soon as the menu closes. HD mode would override it, so it goes off
        findPreference<androidx.preference.ListPreference>(getString(R.string.pref_key_shader_filter))
            ?.setOnPreferenceChangeListener { _, value ->
                preferenceManager.preferenceDataStore?.putBoolean(getString(R.string.pref_key_hd_mode), false)
                // Show it on the paused game behind the menu straight away
                com.swordfish.lemuroid.app.shared.game.ScreenFilterPreview.apply?.invoke(value.toString())
                true
            }

        // The hidden extra, once unlocked: mutes the game and shuffles the big music folder instead
        if (com.swordfish.lemuroid.app.tv.romm.JohnnyMusic.isUnlocked(requireContext())) {
            val johnny =
                androidx.preference.SwitchPreference(preferenceScreen.context).apply {
                    key = JOHNNY
                    title = "Johnny Music"
                    isPersistent = false
                    setIcon(R.drawable.ic_menu_mute)
                }
            preferenceScreen.addPreference(johnny)
            // Set after it's added: adding a switch resets it to off
            johnny.isChecked = com.swordfish.lemuroid.app.tv.romm.JohnnyMusic.isOn
        }

        if (numDisks > 1) {
            GameMenuHelper.setupChangeDiskOption(activity, preferenceScreen, currentDisk, numDisks)
        }

        launchOnState(Lifecycle.State.CREATED) {
            initializeLoadAndSave()
        }

        launchOnState(Lifecycle.State.CREATED) {
            initializeControllers()
        }
    }

    private suspend fun initializeControllers() {
        inputDeviceManager.getGamePadsObservable()
            .safeCollect { setupCoreOptions(it.size) }
    }

    private fun setupCoreOptions(connectedGamePads: Int) {
        val coreOptionsScreen =
            findPreference<PreferenceScreen>(GameMenuHelper.SECTION_CORE_OPTIONS)
                ?: return

        coreOptionsScreen.removeAll()

        CoreOptionsPreferenceHelper.addPreferences(
            coreOptionsScreen,
            game.systemId,
            coreOptions.toList(),
            advancedCoreOptions.toList(),
        )

        CoreOptionsPreferenceHelper.addControllers(
            coreOptionsScreen,
            game.systemId,
            systemCoreConfig.coreID,
            connectedGamePads,
            systemCoreConfig.controllerConfigs,
        )

        // An empty settings page can't be opened at all (pressing it would do nothing), so say why instead
        if (coreOptionsScreen.preferenceCount == 0) {
            coreOptionsScreen.addPreference(
                Preference(coreOptionsScreen.context).apply {
                    title = "No extra settings for this console"
                    summary = "To change which button does what, go to Settings, then Controller buttons, on the home screen."
                    isIconSpaceReserved = false
                },
            )
        }
    }

    private suspend fun initializeLoadAndSave() {
        val saveScreen = findPreference<PreferenceScreen>(GameMenuHelper.SECTION_SAVE_GAME)
        val loadScreen = findPreference<PreferenceScreen>(GameMenuHelper.SECTION_LOAD_GAME)

        saveScreen?.isEnabled = systemCoreConfig.statesSupported
        loadScreen?.isEnabled = systemCoreConfig.statesSupported

        val slotsInfo = statesManager.getSavedSlotsInfo(game, systemCoreConfig.coreID)

        slotsInfo.forEachIndexed { index, saveInfo ->
            val bitmap =
                GameMenuHelper.getSaveStateBitmap(
                    requireContext(),
                    statesPreviewManager,
                    saveInfo,
                    game,
                    systemCoreConfig.coreID,
                    index,
                )

            if (saveScreen != null) {
                GameMenuHelper.addSavePreference(saveScreen, index, saveInfo, bitmap)
            }

            if (loadScreen != null) {
                GameMenuHelper.addLoadPreference(loadScreen, index, saveInfo, bitmap)
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "pref_game_players") {
            startActivity(android.content.Intent(requireContext(), com.swordfish.lemuroid.app.tv.romm.GamePlayersActivity::class.java))
            return true
        }
        if (preference.key == JOHNNY) {
            val turnOn = !com.swordfish.lemuroid.app.tv.romm.JohnnyMusic.isOn
            com.swordfish.lemuroid.app.tv.romm.JohnnyMusic.setOn(requireContext(), turnOn)
            // Game sound goes off with the music on, and back on with it off
            activity?.setResult(
                android.app.Activity.RESULT_OK,
                android.content.Intent().putExtra(com.swordfish.lemuroid.app.shared.GameMenuContract.RESULT_ENABLE_AUDIO, !turnOn),
            )
            activity?.finish()
            return true
        }
        if (GameMenuHelper.onPreferenceTreeClicked(activity, preference)) {
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    companion object {
        private const val JOHNNY = "pref_game_johnny_music"
    }

    @dagger.Module
    class Module
}
