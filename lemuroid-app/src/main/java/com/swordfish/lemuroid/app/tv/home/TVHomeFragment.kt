package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.folderpicker.TVFolderPickerLauncher
import com.swordfish.lemuroid.app.tv.settings.TVSettingsActivity
import com.swordfish.lemuroid.app.tv.shared.GamePresenter
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

class TVHomeFragment : BrowseSupportFragment() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    @Inject
    lateinit var saveSyncManager: SaveSyncManager

    /** The RomM account tile: a connection report (including a cover art test), and a way to sign in again. */
    private fun showRommStatus() {
        val context = requireContext()
        val dialog =
            android.app.AlertDialog.Builder(context)
                .setTitle("RomM account")
                .setMessage("Checking your RomM server…")
                .setPositiveButton("Close", null)
                .setNeutralButton("Sign in again") { _, _ ->
                    startActivity(Intent(context, com.swordfish.lemuroid.app.tv.romm.RommSignInActivity::class.java))
                }
                .show()
        viewLifecycleOwner.lifecycle.coroutineScope.launch {
            val report =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val db =
                        runCatching {
                            retrogradeDb.query("SELECT COUNT(*), SUM(coverFrontUrl IS NOT NULL), MAX(coverFrontUrl) FROM games", null).use {
                                it.moveToFirst()
                                "In the app: ${it.getInt(0)} games, ${it.getInt(1)} with a cover address\nExample: ${it.getString(2) ?: "none"}"
                            }
                        }.getOrElse { "In the app: couldn't read (${it.message})" }
                    com.swordfish.lemuroid.app.tv.romm.RommDiagnostics.report(context.applicationContext) + "\n\n" + db
                }
            dialog.setMessage(report)
        }
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        onItemViewClickedListener =
            OnItemViewClickedListener { _, item, _, _ ->
                when (item) {
                    is Game -> gameInteractor.onGamePlay(item)
                    is InstalledGame -> confirmRemove(item)
                    is StorageSummary -> showDownloadLocation()
                    is InstalledAction ->
                        when (item.type) {
                            InstalledAction.Type.MOVE_ALL_TO_USB -> confirmMoveAllToUsb()
                            InstalledAction.Type.SORT -> {
                                installedSort = if (installedSort == InstalledSort.SIZE) InstalledSort.LOCATION else InstalledSort.SIZE
                                refreshInstalled()
                            }
                        }
                    is MetaSystemInfo -> {
                        val action =
                            TVHomeFragmentDirections.actionNavigationSystemsToNavigationGames(
                                item.metaSystem.name,
                            )
                        findNavController().navigate(action)
                    }
                    is TVSetting -> {
                        when (item.type) {
                            TVSettingType.STOP_RESCAN ->
                                LibraryIndexScheduler.cancelLibrarySync(
                                    requireContext().applicationContext,
                                )
                            TVSettingType.RESCAN ->
                                LibraryIndexScheduler.scheduleLibrarySync(
                                    requireContext().applicationContext,
                                )
                            TVSettingType.ROMM_ACCOUNT -> showRommStatus()
                            TVSettingType.DOWNLOAD_LOCATION -> showDownloadLocation()
                            TVSettingType.SETTINGS -> launchTVSettings()
                            TVSettingType.SHOW_ALL_FAVORITES -> launchFavorites()
                            TVSettingType.SAVE_SYNC ->
                                SaveSyncWork.enqueueManualWork(
                                    requireContext().applicationContext,
                                )
                        }
                    }
                }
            }

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        recreateAdapter(
            includeFavorites = false,
            includeRecentGames = false,
            includeSystems = false,
        )
        setOnSearchClickedListener {
            findNavController().navigate(R.id.navigation_search)
        }

        val factory = TVHomeViewModel.Factory(retrogradeDb, requireContext().applicationContext)
        val homeViewModel = ViewModelProvider(this, factory)[TVHomeViewModel::class.java]

        launchOnState(Lifecycle.State.RESUMED) {
            homeViewModel.getViewStates()
                .collect { update(it) }
        }
    }

    private fun update(viewState: TVHomeViewModel.HomeViewState) {
        val adapterHasFavorites = findAdapterById<ObjectAdapter>(FAVORITES_ADAPTER) != null
        val adapterHasGames = findAdapterById<ObjectAdapter>(RECENTS_ADAPTER) != null
        val adapterHasSystems = findAdapterById<ObjectAdapter>(SYSTEM_ADAPTER) != null

        val favoritesChanged = adapterHasFavorites != viewState.favoritesGames.isNotEmpty()
        val recentsChanged = adapterHasGames != viewState.recentGames.isNotEmpty()
        val systemsChanged = adapterHasSystems != viewState.metaSystems.isNotEmpty()

        if (favoritesChanged || recentsChanged || systemsChanged) {
            recreateAdapter(
                viewState.favoritesGames.isNotEmpty(),
                viewState.recentGames.isNotEmpty(),
                viewState.metaSystems.isNotEmpty(),
            )
        }

        findAdapterById<ArrayObjectAdapter>(FAVORITES_ADAPTER)?.apply {
            if (viewState.favoritesGames.size <= TVHomeViewModel.CAROUSEL_MAX_ITEMS) {
                setItems(viewState.favoritesGames, LEANBACK_MULTI_DIFF_CALLBACK)
            } else {
                val allItems =
                    viewState.favoritesGames.subList(0, TVHomeViewModel.CAROUSEL_MAX_ITEMS) +
                        listOf(TVSetting(TVSettingType.SHOW_ALL_FAVORITES))
                setItems(allItems, LEANBACK_MULTI_DIFF_CALLBACK)
            }
        }

        findAdapterById<ArrayObjectAdapter>(RECENTS_ADAPTER)
            ?.setItems(viewState.recentGames, LEANBACK_GAME_DIFF_CALLBACK)

        findAdapterById<ArrayObjectAdapter>(SYSTEM_ADAPTER)
            ?.setItems(viewState.metaSystems, LEANBACK_SYSTEM_DIFF_CALLBACK)

        findAdapterById<ArrayObjectAdapter>(SETTINGS_ADAPTER)?.setItems(
            buildSettingsRowItems(viewState.indexInProgress, viewState.scanInProgress),
            LEANBACK_SETTING_DIFF_CALLBACK,
        )
    }

    private fun <T> findAdapterById(id: Long): T? {
        for (i: Int in 0 until adapter.size()) {
            val listRow = adapter.get(i) as ListRow
            if (listRow.headerItem.id == id) {
                return listRow.adapter as T
            }
        }
        return null
    }

    private fun recreateAdapter(
        includeFavorites: Boolean,
        includeRecentGames: Boolean,
        includeSystems: Boolean,
    ) {
        val result = ArrayObjectAdapter(ListRowPresenter())
        val cardSize = resources.getDimensionPixelSize(com.swordfish.lemuroid.lib.R.dimen.card_size)
        val systemsCardPadding =
            resources.getDimensionPixelSize(
                com.swordfish.lemuroid.lib.R.dimen.systems_card_padding,
            )
        val settingsCardPadding =
            resources.getDimensionPixelSize(
                com.swordfish.lemuroid.lib.R.dimen.settings_card_padding,
            )

        if (includeFavorites) {
            val presenter = ClassPresenterSelector()
            presenter.addClassPresenter(Game::class.java, GamePresenter(cardSize, gameInteractor))
            presenter.addClassPresenter(TVSetting::class.java, SettingPresenter(cardSize, settingsCardPadding))
            val favouritesItems = ArrayObjectAdapter(presenter)
            val title = resources.getString(R.string.tv_home_section_favorites)
            result.add(ListRow(HeaderItem(FAVORITES_ADAPTER, title), favouritesItems))
        }

        if (includeRecentGames) {
            val recentItems = ArrayObjectAdapter(GamePresenter(cardSize, gameInteractor))
            val title = resources.getString(R.string.tv_home_section_recents)
            result.add(ListRow(HeaderItem(RECENTS_ADAPTER, title), recentItems))
        }

        if (includeSystems) {
            val systemItems = ArrayObjectAdapter(SystemPresenter(cardSize, systemsCardPadding))
            val title = resources.getString(R.string.tv_home_section_systems)
            result.add(ListRow(HeaderItem(SYSTEM_ADAPTER, title), systemItems))
        }

        val settingsItems = ArrayObjectAdapter(SettingPresenter(cardSize, settingsCardPadding))
        settingsItems.setItems(
            buildSettingsRowItems(indexInProgress = false, scanInProgress = false),
            LEANBACK_SETTING_DIFF_CALLBACK,
        )
        val settingsTitle = resources.getString(R.string.tv_home_section_settings)
        result.add(ListRow(HeaderItem(SETTINGS_ADAPTER, settingsTitle), settingsItems))

        // Installed: the TV's free space and the games downloaded to it
        installedItems = ArrayObjectAdapter(InstalledPresenter(cardSize, settingsCardPadding))
        result.add(ListRow(HeaderItem(INSTALLED_ADAPTER, "Installed"), installedItems))

        adapter = result
        refreshInstalled()
    }

    private var installedItems: ArrayObjectAdapter? = null

    private var installedSort: InstalledSort
        get() =
            runCatching {
                InstalledSort.valueOf(
                    requireContext().getSharedPreferences("romm_storage", Context.MODE_PRIVATE)
                        .getString("installed_sort", InstalledSort.SIZE.name)!!,
                )
            }.getOrDefault(InstalledSort.SIZE)
        set(value) {
            requireContext().getSharedPreferences("romm_storage", Context.MODE_PRIVATE)
                .edit().putString("installed_sort", value.name).apply()
        }

    /** Re-reads which games are on the TV and the USB drive, and how much space is left on each. */
    private fun refreshInstalled() {
        val context = context?.applicationContext ?: return
        val sort = installedSort
        viewLifecycleOwnerLiveData.value?.lifecycle?.coroutineScope?.launch {
            val items =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val files = com.swordfish.lemuroid.lib.romm.RommGameFiles(context)
                    val games =
                        files.installed().mapNotNull { entry ->
                            val gameId =
                                retrogradeDb.query("SELECT id FROM games WHERE fileUri LIKE ?", arrayOf<Any?>("romm://rom/${entry.romId}?%")).use {
                                    if (it.moveToFirst()) it.getInt(0) else null
                                } ?: return@mapNotNull null
                            retrogradeDb.gameDao().selectById(gameId)?.let { InstalledGame(it, entry.romId, entry.sizeBytes, entry.isUsb) }
                        }
                    val sorted =
                        when (sort) {
                            InstalledSort.SIZE -> games.sortedByDescending { it.sizeBytes }
                            InstalledSort.LOCATION -> games.sortedWith(compareByDescending<InstalledGame> { it.isUsb }.thenByDescending { it.sizeBytes })
                        }
                    val target = files.downloadTarget()
                    val summaries =
                        files.storages().map { storage ->
                            val (free, total) = files.space(storage)
                            val onIt = games.filter { it.isUsb == storage.isUsb }
                            StorageSummary(storage.isUsb, free, total, onIt.sumOf { it.sizeBytes }, onIt.size, storage == target)
                        }
                    val actions = mutableListOf<InstalledAction>()
                    val onTv = games.filter { !it.isUsb }
                    if (files.usbStorage != null && onTv.isNotEmpty()) {
                        actions += InstalledAction(InstalledAction.Type.MOVE_ALL_TO_USB, "Move all to USB", "${onTv.size} games, ${formatBytes(onTv.sumOf { it.sizeBytes })}")
                    }
                    actions += InstalledAction(InstalledAction.Type.SORT, "Sort: ${sort.label}", "Click to sort by ${if (sort == InstalledSort.SIZE) "location" else "size"}")
                    summaries + actions + sorted
                }
            installedItems?.setItems(items, null)
        }
    }

    /** Settings: where new downloads go. The USB drive can only be chosen while one is plugged in. */
    private fun showDownloadLocation() {
        val context = requireContext()
        viewLifecycleOwner.lifecycle.coroutineScope.launch {
            val files = com.swordfish.lemuroid.lib.romm.RommGameFiles(context.applicationContext)
            val (tvLabel, usbLabel, usbConnected) =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val usb = files.usbStorage
                    Triple(
                        "This TV  (${formatBytes(files.space(files.storages().first()).first)} free)",
                        if (usb != null) "USB drive  (${formatBytes(files.space(usb).first)} free)" else "USB drive  (not connected)",
                        usb != null,
                    )
                }
            val preferUsb = com.swordfish.lemuroid.lib.romm.RommGameFiles.preferUsb(context)
            android.app.AlertDialog.Builder(context)
                .setTitle("Download new games to")
                .setSingleChoiceItems(arrayOf(tvLabel, usbLabel), if (preferUsb) 1 else 0) { dialog, which ->
                    if (which == 1 && !usbConnected) {
                        android.widget.Toast.makeText(context, "Plug in a USB drive first.", android.widget.Toast.LENGTH_LONG).show()
                        return@setSingleChoiceItems
                    }
                    com.swordfish.lemuroid.lib.romm.RommGameFiles.setPreferUsb(context, which == 1)
                    dialog.dismiss()
                    refreshInstalled()
                }
                .setMessage(null)
                .setNegativeButton("Close", null)
                .show()
        }
    }

    /** Clicking an installed game: delete it, move it to the other storage, or cancel. */
    private fun confirmRemove(item: InstalledGame) {
        val context = requireContext()
        val files = com.swordfish.lemuroid.lib.romm.RommGameFiles(context.applicationContext)
        val builder =
            android.app.AlertDialog.Builder(context)
                .setTitle("Delete ${item.game.displayName()}?")
                .setMessage(
                    "It's on the ${if (item.isUsb) "USB drive" else "TV"} and takes ${formatBytes(item.sizeBytes)}. " +
                        "It stays in your RomM library and downloads again the next time you play it. Your saves are kept.",
                )
                .setPositiveButton("Delete") { _, _ ->
                    viewLifecycleOwner.lifecycle.coroutineScope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { files.remove(item.romId) }
                        refreshInstalled()
                    }
                }
                .setNegativeButton("Cancel", null)
        if (item.isUsb || files.usbStorage != null) {
            builder.setNeutralButton(if (item.isUsb) "Move to TV" else "Move to USB") { _, _ -> moveGames(listOf(item), toUsb = !item.isUsb) }
        }
        builder.show()
    }

    private fun confirmMoveAllToUsb() {
        val context = requireContext()
        viewLifecycleOwner.lifecycle.coroutineScope.launch {
            val onTv =
                installedItems?.let { adapter -> (0 until adapter.size()).mapNotNull { adapter.get(it) as? InstalledGame } }
                    ?.filter { !it.isUsb }.orEmpty()
            if (onTv.isEmpty()) return@launch
            android.app.AlertDialog.Builder(context)
                .setTitle("Move all games to the USB drive?")
                .setMessage("${onTv.size} games, ${formatBytes(onTv.sumOf { it.sizeBytes })}. Keep the drive plugged in until it's done.")
                .setPositiveButton("Move") { _, _ -> moveGames(onTv, toUsb = true) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Moves games one by one with a progress box, then reports anything that didn't fit or failed. */
    private fun moveGames(
        games: List<InstalledGame>,
        toUsb: Boolean,
    ) {
        val context = requireContext()
        val progress =
            android.app.AlertDialog.Builder(context)
                .setTitle(if (toUsb) "Moving to the USB drive" else "Moving to the TV")
                .setMessage("Starting…")
                .setCancelable(false)
                .show()
        viewLifecycleOwner.lifecycle.coroutineScope.launch {
            val files = com.swordfish.lemuroid.lib.romm.RommGameFiles(context.applicationContext)
            val failed = mutableListOf<String>()
            games.forEachIndexed { i, g ->
                progress.setMessage("${i + 1} of ${games.size}: ${g.game.displayName()} (${formatBytes(g.sizeBytes)})")
                val ok =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (toUsb) files.moveToUsb(g.romId) else files.moveToTv(g.romId)
                    }
                if (!ok) failed += g.game.displayName()
            }
            progress.dismiss()
            if (failed.isNotEmpty()) {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Some games weren't moved")
                    .setMessage(
                        "There wasn't enough space, or the drive was removed. They're still where they were:\n\n" +
                            failed.joinToString("\n"),
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            refreshInstalled()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInstalled()
    }

    private fun buildSettingsRowItems(
        indexInProgress: Boolean,
        scanInProgress: Boolean,
    ): List<TVSetting> {
        return mutableListOf<TVSetting>().apply {
            if (saveSyncManager.isSupported() && saveSyncManager.isConfigured()) {
                add(TVSetting(TVSettingType.SAVE_SYNC, !indexInProgress))
            }
            if (scanInProgress) {
                add(TVSetting(TVSettingType.STOP_RESCAN, true))
            } else {
                add(TVSetting(TVSettingType.RESCAN, !indexInProgress))
            }

            add(TVSetting(TVSettingType.ROMM_ACCOUNT, !indexInProgress))
            add(TVSetting(TVSettingType.DOWNLOAD_LOCATION, true))
            add(TVSetting(TVSettingType.SETTINGS, !indexInProgress))
        }
    }

    private fun launchFolderPicker() {
        if (TVHelper.isSAFSupported(requireContext())) {
            StorageFrameworkPickerLauncher.pickFolder(requireContext())
        } else {
            TVFolderPickerLauncher.pickFolder(requireContext())
        }
    }

    private fun launchTVSettings() {
        startActivity(Intent(requireContext(), TVSettingsActivity::class.java))
    }

    private fun launchFavorites() {
        findNavController().navigate(R.id.navigation_favorites)
    }

    companion object {
        const val RECENTS_ADAPTER = 1L
        const val SYSTEM_ADAPTER = 2L
        const val SETTINGS_ADAPTER = 3L
        const val FAVORITES_ADAPTER = 4L
        const val INSTALLED_ADAPTER = 5L

        val LEANBACK_MULTI_DIFF_CALLBACK =
            object : DiffCallback<Any>() {
                override fun areContentsTheSame(
                    oldItem: Any,
                    newItem: Any,
                ): Boolean {
                    return when {
                        (oldItem is Game && newItem is Game) -> {
                            LEANBACK_GAME_DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        }
                        (oldItem is TVSetting && newItem is TVSetting) -> {
                            LEANBACK_SETTING_DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        }
                        else -> false
                    }
                }

                override fun areItemsTheSame(
                    oldItem: Any,
                    newItem: Any,
                ): Boolean {
                    return when {
                        (oldItem is Game && newItem is Game) -> {
                            LEANBACK_GAME_DIFF_CALLBACK.areItemsTheSame(oldItem, newItem)
                        }
                        (oldItem is TVSetting && newItem is TVSetting) -> {
                            LEANBACK_SETTING_DIFF_CALLBACK.areItemsTheSame(oldItem, newItem)
                        }
                        else -> false
                    }
                }
            }

        val LEANBACK_GAME_DIFF_CALLBACK =
            object : DiffCallback<Game>() {
                override fun areContentsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem == newItem
                }

                override fun areItemsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem.id == newItem.id
                }
            }

        val LEANBACK_SYSTEM_DIFF_CALLBACK =
            object : DiffCallback<MetaSystemInfo>() {
                override fun areContentsTheSame(
                    oldInfo: MetaSystemInfo,
                    newInfo: MetaSystemInfo,
                ): Boolean {
                    return oldInfo == newInfo
                }

                override fun areItemsTheSame(
                    oldInfo: MetaSystemInfo,
                    newInfo: MetaSystemInfo,
                ): Boolean {
                    return oldInfo.metaSystem.name == newInfo.metaSystem.name
                }
            }

        val LEANBACK_SETTING_DIFF_CALLBACK =
            object : DiffCallback<TVSetting>() {
                override fun areContentsTheSame(
                    oldItem: TVSetting,
                    newItem: TVSetting,
                ): Boolean {
                    return oldItem == newItem
                }

                override fun areItemsTheSame(
                    oldItem: TVSetting,
                    newItem: TVSetting,
                ): Boolean {
                    return oldItem.type == newItem.type
                }
            }
    }

    @dagger.Module
    class Module
}
