package com.swordfish.lemuroid.app.tv.home

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.compose.AsyncImage
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.romm.AppUpdater
import com.swordfish.lemuroid.app.tv.romm.RommDiagnostics
import com.swordfish.lemuroid.app.tv.romm.RommSignInActivity
import com.swordfish.lemuroid.app.tv.settings.TVSettingsActivity
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.romm.RommGameFiles
import dagger.android.support.AndroidSupportInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Big Picture screens: a top bar (Home, Platforms, Installed, Settings, Search), a home page with
 * Continue playing, Favorites, Platforms and Recently added, a cover grid per platform with filters,
 * the Installed page (storage, moving and deleting downloads) and the Settings page.
 */
class BigPictureFragment : Fragment() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    private enum class Tab(val label: String) {
        HOME("Home"),
        PLATFORMS("Platforms"),
        INSTALLED("Installed"),
        SETTINGS("Settings"),
        SEARCH("Search"),
    }

    private enum class LibraryFilter(val label: String) {
        ALL("All games"),
        DOWNLOADED("Downloaded"),
        FAVORITES("Favorites"),
    }

    private var tab by mutableStateOf(Tab.HOME)
    private var home by mutableStateOf(TVHomeViewModel.HomeViewState())
    private var recents by mutableStateOf<List<Game>>(emptyList())
    private var favorites by mutableStateOf<List<Game>>(emptyList())
    private var lastAdded by mutableStateOf<List<Game>>(emptyList())
    private var installedIds by mutableStateOf<Set<Int>>(emptySet())

    private var librarySystem by mutableStateOf<MetaSystemInfo?>(null)
    private var libraryGames by mutableStateOf<List<Game>>(emptyList())
    private var libraryFilter by mutableStateOf(LibraryFilter.ALL)
    private var libraryRecentFirst by mutableStateOf(false)

    private var installed by mutableStateOf<List<InstalledGame>>(emptyList())
    private var storage by mutableStateOf<List<StorageSummary>>(emptyList())
    private var usbConnected by mutableStateOf(false)
    private var installedSort by mutableStateOf(InstalledSort.SIZE)

    private var touchVibration by mutableStateOf("press")

    /** Bumped when the screen comes back, so Settings re-reads things changed elsewhere (like RetroAchievements). */
    private var resumeCount by mutableStateOf(0)

    private var syncStatus by mutableStateOf<com.swordfish.lemuroid.lib.romm.RommLibraryStatus.Status?>(null)

    /** Picks up games added to RomM since the last look: syncs when the app comes back and the last sync is over 15 minutes old. */
    private fun syncIfStale() {
        val context = context?.applicationContext ?: return
        val status = com.swordfish.lemuroid.lib.romm.RommLibraryStatus.read(context)
        syncStatus = status
        if (!com.swordfish.lemuroid.lib.romm.RommSession(context).isSignedIn) return
        if (System.currentTimeMillis() - status.attemptAt > 15 * 60 * 1000L) {
            LibraryIndexScheduler.scheduleLibrarySync(context)
        }
    }

    private fun syncProblem(): String? {
        val status = syncStatus ?: return null
        if (status.error == null || home.indexInProgress) return null
        return "The last library sync didn't finish (${status.error}). Got ${status.listed} of ${status.total} games. " +
            "Settings → Sync library tries again."
    }

    private fun syncSummary(): String {
        val status = context?.let { com.swordfish.lemuroid.lib.romm.RommLibraryStatus.read(it) } ?: return "Fetch new games and covers from RomM"
        val time: (Long) -> String = { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() }
        return when {
            status.error != null -> "Last sync stopped: ${status.error}. Got ${status.listed} of ${status.total}."
            status.successAt > 0 -> "${status.listed} games from RomM (${status.total} entries on the server) · synced ${time(status.successAt)}"
            else -> "Fetch new games and covers from RomM"
        }
    }

    private fun emulatorPrefs() =
        com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper.getSharedPreferences(requireContext().applicationContext)

    private var searchGames by mutableStateOf<List<Game>?>(null)
    private var searchQuery by mutableStateOf("")
    private var searchSystem by mutableStateOf<String?>(null)
    private var recentSearches by mutableStateOf<List<String>>(emptyList())

    private val back =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (librarySystem != null) {
                    librarySystem = null
                } else {
                    tab = Tab.HOME
                }
                updateBack()
            }
        }

    private fun updateBack() {
        back.isEnabled = librarySystem != null || tab != Tab.HOME
    }

    private fun go(next: Tab) {
        librarySystem = null
        tab = next
        updateBack()
        if (next == Tab.INSTALLED) refreshInstalled()
        if (next == Tab.SEARCH) loadSearchGames()
        if (next == Tab.SETTINGS) {
            touchVibration = emulatorPrefs().getString(getString(R.string.pref_key_haptic_feedback_mode), "press") ?: "press"
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
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Screen() }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        updateBack()

        val factory = TVHomeViewModel.Factory(retrogradeDb, requireContext().applicationContext)
        val viewModel = ViewModelProvider(this, factory)[TVHomeViewModel::class.java]
        val scope = viewLifecycleOwner.lifecycleScope
        scope.launch { viewModel.getViewStates().collect { home = it } }
        scope.launch { retrogradeDb.gameDao().selectRecents(12).collect { recents = it } }
        scope.launch { retrogradeDb.gameDao().selectAllFavorites().collect { favorites = it } }
        scope.launch { retrogradeDb.gameDao().selectLastAdded(18).collect { lastAdded = it } }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
        syncIfStale()
        refreshInstalledIds()
        if (tab == Tab.INSTALLED) refreshInstalled()
        librarySystem?.let { loadLibrary(it) }
    }

    private fun refreshInstalledIds() {
        val context = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            installedIds = withContext(Dispatchers.IO) { RommGameFiles(context).downloadedIds() }
        }
    }

    private fun romId(game: Game): Int? =
        if (game.fileUri.startsWith("romm://")) RommGameFiles.romId(Uri.parse(game.fileUri)) else null

    private fun isInstalled(game: Game): Boolean = romId(game)?.let { it in installedIds } ?: false

    private fun openLibrary(system: MetaSystemInfo) {
        libraryGames = emptyList()
        libraryFilter = LibraryFilter.ALL
        librarySystem = system
        tab = Tab.PLATFORMS
        updateBack()
        loadLibrary(system)
    }

    private fun loadLibrary(system: MetaSystemInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            val games =
                withContext(Dispatchers.IO) {
                    retrogradeDb.gameDao().listBySystems(system.metaSystem.systemIDs.map { it.dbname })
                }
            if (librarySystem == system) libraryGames = games
        }
    }

    // ---- the screens

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().background(Ground)) {
            TopBar()
            val system = librarySystem
            when {
                system != null -> Library(system)
                tab == Tab.HOME -> Home()
                tab == Tab.PLATFORMS -> Platforms()
                tab == Tab.INSTALLED -> Installed()
                tab == Tab.SEARCH -> Search()
                else -> Settings()
            }
        }
    }

    @Composable
    private fun TopBar() {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFB4EE7E), Color(0xFF46AA28), Color(0xFF0E5C0E)))),
            )
            Spacer(Modifier.width(4.dp))
            Text("RomM TV", color = TextColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(22.dp))
            Tab.values().filter { it != Tab.SEARCH }.forEach { t ->
                TabButton(t.label, selected = tab == t, modifier = if (t == Tab.HOME) Modifier.focusRequester(first) else Modifier) { go(t) }
            }
            Spacer(Modifier.weight(1f))
            if (home.indexInProgress) Text("Syncing library…", color = Lime, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            TabButton("Search", selected = tab == Tab.SEARCH) { go(Tab.SEARCH) }
        }
    }

    @Composable
    private fun Home() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recents.isEmpty() && lastAdded.isEmpty()) {
                Text(
                    if (home.indexInProgress) {
                        "Loading your library from RomM…"
                    } else {
                        "No games yet. Open Settings and choose Sync library, or check your RomM account there."
                    },
                    color = Quiet,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
                )
            }

            if (recents.isNotEmpty()) {
                SectionTitle("CONTINUE PLAYING")
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { ContinueCard(recents.first()) }
                    items(recents.drop(1)) { game -> GameTile(game) }
                }
            }

            if (favorites.isNotEmpty()) {
                SectionTitle("FAVORITES")
                GameRow(favorites)
            }

            if (home.metaSystems.isNotEmpty()) {
                SectionTitle("PLATFORMS")
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(home.metaSystems) { system -> PlatformTile(system, Modifier.width(168.dp)) }
                }
            }

            if (lastAdded.isNotEmpty()) {
                SectionTitle("RECENTLY ADDED")
                GameRow(lastAdded)
            }
        }
    }

    @Composable
    private fun GameRow(games: List<Game>) {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(games) { game -> GameTile(game) }
        }
    }

    /** The most recent game, large: cover, system, title. */
    @Composable
    private fun ContinueCard(game: Game) {
        FocusBox(Modifier.width(430.dp).height(TILE_COVER_HEIGHT + TILE_INFO_HEIGHT), onClick = { gameInteractor.onGamePlay(game) }) {
            Row(Modifier.fillMaxSize()) {
                Cover(game, Modifier.width(150.dp).height(TILE_COVER_HEIGHT + TILE_INFO_HEIGHT))
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        GameUtils.getGameSubtitle(requireContext(), game).uppercase(),
                        color = Lime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    )
                    Text(
                        game.title,
                        color = TextColor,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (isInstalled(game)) "Downloaded and ready to play" else "Downloads when you play",
                        color = if (isInstalled(game)) Lime else Quiet,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun Platforms() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            syncProblem()?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 14.sp) }
            if (home.metaSystems.isEmpty()) Text("No platforms yet.", color = Quiet, fontSize = 16.sp)
            home.metaSystems.chunked(5).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { PlatformTile(it, Modifier.weight(1f)) }
                    repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun PlatformTile(
        system: MetaSystemInfo,
        modifier: Modifier = Modifier,
    ) {
        FocusBox(modifier.height(84.dp), onClick = { openLibrary(system) }) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformIcon(system, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        system.getName(requireContext()),
                        color = TextColor,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${system.count} game${if (system.count == 1) "" else "s"}", color = Lime, fontSize = 12.sp)
                }
            }
        }
    }

    /** The console's logo from RomM (the same icons RomM's website shows), or nothing if RomM has none for it. */
    @Composable
    private fun PlatformIcon(
        system: MetaSystemInfo,
        modifier: Modifier = Modifier,
    ) {
        val context = requireContext()
        val candidates =
            remember(system) {
                val server = com.swordfish.lemuroid.lib.romm.RommSession(context).serverUrl
                system.metaSystem.systemIDs.flatMap { PlatformIcons.slugs(it) }.distinct()
                    .flatMap { listOf("$server/assets/platforms/$it.svg", "$server/assets/platforms/$it.ico") }
            }
        var index by remember(system) { mutableStateOf(0) }
        Box(modifier, contentAlignment = Alignment.Center) {
            val url = candidates.getOrNull(index) ?: return@Box
            AsyncImage(
                model =
                    coil.request.ImageRequest.Builder(context)
                        .data(url)
                        .apply { if (url.endsWith(".svg")) decoderFactory(coil.decode.SvgDecoder.Factory()) }
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { index++ },
            )
        }
    }

    /** One platform's games: a cover grid, seven across, with filters and sorting. */
    @Composable
    private fun Library(system: MetaSystemInfo) {
        val shown =
            libraryGames
                .filter {
                    when (libraryFilter) {
                        LibraryFilter.ALL -> true
                        LibraryFilter.DOWNLOADED -> isInstalled(it)
                        LibraryFilter.FAVORITES -> it.isFavorite
                    }
                }
                .let { list -> if (libraryRecentFirst) list.sortedByDescending { it.lastPlayedAt ?: 0L } else list }
        val firstGame = remember { FocusRequester() }
        LaunchedEffect(system, libraryGames.isNotEmpty()) {
            if (libraryGames.isNotEmpty()) runCatching { firstGame.requestFocus() }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(system.getName(requireContext()), color = TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${shown.size} games", color = Quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                LibraryFilter.values().forEach { f ->
                    TabButton(f.label, selected = libraryFilter == f) { libraryFilter = f }
                }
                TabButton(if (libraryRecentFirst) "Sort: Recently played" else "Sort: A to Z", selected = false) {
                    libraryRecentFirst = !libraryRecentFirst
                }
            }
            if (shown.isEmpty()) {
                Text(
                    when {
                        libraryGames.isEmpty() -> "Loading…"
                        libraryFilter == LibraryFilter.DOWNLOADED -> "No games from this platform are downloaded yet."
                        else -> "No favorites on this platform yet."
                    },
                    color = Quiet,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 28.dp, start = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shown, key = { _, game -> game.id }) { index, game ->
                    GameTile(game, if (index == 0) Modifier.focusRequester(firstGame) else Modifier, fill = true)
                }
            }
        }
    }

    // ---- Search

    private fun loadSearchGames() {
        val context = context?.applicationContext ?: return
        recentSearches = GameSearch.recent(context)
        viewLifecycleOwner.lifecycleScope.launch {
            searchGames = withContext(Dispatchers.IO) { retrogradeDb.gameDao().listAll() }
        }
    }

    private fun rememberSearch(query: String) {
        val context = context?.applicationContext ?: return
        if (query.isBlank()) return
        recentSearches = GameSearch.remember(context, query)
    }

    /**
     * Search across every platform as you type. Results are a cover grid, best matches first, and can be
     * narrowed to one platform. With nothing typed it shows your recent searches and recently played games.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    private fun Search() {
        val context = requireContext()
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val field = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

        var debounced by remember { mutableStateOf(searchQuery) }
        LaunchedEffect(searchQuery) {
            kotlinx.coroutines.delay(250)
            debounced = searchQuery
        }
        val all = searchGames
        val matches = remember(all, debounced) { if (all == null) emptyList() else GameSearch.search(all, debounced) }
        val systems = remember(matches) { matches.map { it.systemId }.distinct() }
        val shown = matches.filter { searchSystem == null || it.systemId == searchSystem }

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                var fieldFocused by remember { mutableStateOf(false) }
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        searchSystem = null
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextColor, fontSize = 20.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Lime),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            rememberSearch(searchQuery)
                            keyboard?.hide()
                            focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                        }),
                    modifier =
                        Modifier
                            .width(620.dp)
                            .focusRequester(field)
                            .onFocusChanged { fieldFocused = it.isFocused }
                            .onPreviewKeyEvent { e ->
                                // The D-pad leaves the box downwards to the results, like everywhere else
                                if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                    e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                ) {
                                    keyboard?.hide()
                                    rememberSearch(searchQuery)
                                    focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                                } else {
                                    false
                                }
                            },
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .border(2.dp, if (fieldFocused) Lime else Line, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .background(Panel)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text("Type a game name. OK opens the keyboard.", color = Quiet, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    when {
                        all == null -> "Loading…"
                        debounced.isBlank() -> "${all.size} games in your library"
                        else -> "${shown.size} result${if (shown.size == 1) "" else "s"}"
                    },
                    color = Quiet,
                    fontSize = 14.sp,
                )
            }

            if (debounced.isBlank()) {
                if (recentSearches.isNotEmpty()) {
                    Text("RECENT SEARCHES", color = Quiet, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentSearches) { q ->
                            TabButton(q, selected = false) {
                                searchQuery = q
                                searchSystem = null
                            }
                        }
                    }
                }
                if (recents.isNotEmpty()) {
                    Text("RECENTLY PLAYED", color = Quiet, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recents) { game -> GameTile(game) }
                    }
                }
                return@Column
            }

            if (systems.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    item { TabButton("All platforms", selected = searchSystem == null) { searchSystem = null } }
                    items(systems) { id ->
                        val count = matches.count { it.systemId == id }
                        TabButton("${systemShortName(context, id)} ($count)", selected = searchSystem == id) { searchSystem = id }
                    }
                }
            }
            if (shown.isEmpty() && all != null) {
                Text(
                    "No games match \"$debounced\". Try fewer or different words.",
                    color = Quiet,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 28.dp, start = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shown, key = { _, game -> game.id }) { _, game ->
                    GameTile(game, fill = true, onClick = {
                        rememberSearch(searchQuery)
                        gameInteractor.onGamePlay(game)
                    })
                }
            }
        }
    }

    private fun systemShortName(
        context: Context,
        systemId: String,
    ): String =
        runCatching { context.getString(com.swordfish.lemuroid.lib.library.GameSystem.findById(systemId).shortTitleResId) }
            .getOrDefault(systemId.uppercase())

    // ---- Installed

    @Composable
    private fun Installed() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                storage.forEach { s ->
                    FocusBox(Modifier.width(250.dp).height(84.dp), onClick = { showDownloadLocation() }) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                (if (s.isUsb) "USB drive" else "This ${com.swordfish.lemuroid.lib.romm.Device.word}") + ": ${formatBytes(s.freeBytes)} free" +
                                    (if (s.isDownloadTarget) "  ✓" else ""),
                                color = TextColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${s.gamesCount} games, ${formatBytes(s.gamesBytes)} of ${formatBytes(s.totalBytes)}",
                                color = if (s.isDownloadTarget) Lime else Quiet,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                val onTv = installed.filter { !it.isUsb }
                if (usbConnected && onTv.isNotEmpty()) {
                    ActionTile("Move all to USB", "${onTv.size} games, ${formatBytes(onTv.sumOf { it.sizeBytes })}") { confirmMoveAllToUsb() }
                }
                ActionTile("Sort: ${installedSort.label}", "Switch to ${if (installedSort == InstalledSort.SIZE) "location" else "size"}") {
                    installedSort = if (installedSort == InstalledSort.SIZE) InstalledSort.LOCATION else InstalledSort.SIZE
                    refreshInstalled()
                }
            }
            if (installed.isEmpty()) {
                Text("No games are downloaded yet. A game downloads the first time you play it.", color = Quiet, fontSize = 15.sp)
            }
            installed.chunked(7).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            GameTile(
                                item.game,
                                fill = true,
                                subtitle = formatBytes(item.sizeBytes) + if (item.isUsb) " · USB" else " · ${com.swordfish.lemuroid.lib.romm.Device.word}",
                                onClick = { confirmRemove(item) },
                            )
                        }
                    }
                    repeat(7 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun ActionTile(
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ) {
        FocusBox(Modifier.width(190.dp).height(84.dp), onClick = onClick) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Quiet, fontSize = 12.sp)
            }
        }
    }

    /** Re-reads which games are on the TV and the USB drive, and how much space is left on each. */
    private fun refreshInstalled() {
        val context = context?.applicationContext ?: return
        val sort = installedSort
        viewLifecycleOwner.lifecycleScope.launch {
            val files = RommGameFiles(context)
            val (games, summaries, usb) =
                withContext(Dispatchers.IO) {
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
                        files.storages().map { s ->
                            val (free, total) = files.space(s)
                            val onIt = games.filter { it.isUsb == s.isUsb }
                            StorageSummary(s.isUsb, free, total, onIt.sumOf { it.sizeBytes }, onIt.size, s == target)
                        }
                    Triple(sorted, summaries, files.usbStorage != null)
                }
            installed = games
            storage = summaries
            usbConnected = usb
            installedIds = games.map { it.romId }.toSet()
        }
    }

    /** Where new downloads go. The USB drive can only be chosen while one is plugged in. */
    private fun showDownloadLocation() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val files = RommGameFiles(context.applicationContext)
            val (tvLabel, usbLabel, connected) =
                withContext(Dispatchers.IO) {
                    val usb = files.usbStorage
                    Triple(
                        "This ${com.swordfish.lemuroid.lib.romm.Device.word}  (${formatBytes(files.space(files.storages().first()).first)} free)",
                        if (usb != null) "USB drive  (${formatBytes(files.space(usb).first)} free)" else "USB drive  (not connected)",
                        usb != null,
                    )
                }
            AlertDialog.Builder(context)
                .setTitle("Download new games to")
                .setSingleChoiceItems(arrayOf(tvLabel, usbLabel), if (RommGameFiles.preferUsb(context)) 1 else 0) { dialog, which ->
                    if (which == 1 && !connected) {
                        Toast.makeText(context, "Plug in a USB drive first.", Toast.LENGTH_LONG).show()
                        return@setSingleChoiceItems
                    }
                    RommGameFiles.setPreferUsb(context, which == 1)
                    dialog.dismiss()
                    refreshInstalled()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    /** Clicking an installed game: delete it, move it to the other storage, or cancel. */
    private fun confirmRemove(item: InstalledGame) {
        val context = requireContext()
        val files = RommGameFiles(context.applicationContext)
        val builder =
            AlertDialog.Builder(context)
                .setTitle("Delete ${item.game.title}?")
                .setMessage(
                    "It's on the ${if (item.isUsb) "USB drive" else com.swordfish.lemuroid.lib.romm.Device.word} and takes ${formatBytes(item.sizeBytes)}. " +
                        "It stays in your RomM library and downloads again the next time you play it. Your saves are kept.",
                )
                .setPositiveButton("Delete") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { files.remove(item.romId) }
                        refreshInstalled()
                    }
                }
                .setNegativeButton("Cancel", null)
        if (item.isUsb || usbConnected) {
            builder.setNeutralButton(if (item.isUsb) "Move to ${com.swordfish.lemuroid.lib.romm.Device.word}" else "Move to USB") { _, _ -> moveGames(listOf(item), toUsb = !item.isUsb) }
        }
        builder.show()
    }

    private fun confirmMoveAllToUsb() {
        val onTv = installed.filter { !it.isUsb }
        if (onTv.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("Move all games to the USB drive?")
            .setMessage("${onTv.size} games, ${formatBytes(onTv.sumOf { it.sizeBytes })}. Keep the drive plugged in until it's done.")
            .setPositiveButton("Move") { _, _ -> moveGames(onTv, toUsb = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Moves games one by one with a progress box, then reports anything that didn't fit or failed. */
    private fun moveGames(
        games: List<InstalledGame>,
        toUsb: Boolean,
    ) {
        val context = requireContext()
        val progress =
            AlertDialog.Builder(context)
                .setTitle(if (toUsb) "Moving to the USB drive" else "Moving to the ${com.swordfish.lemuroid.lib.romm.Device.word}")
                .setMessage("Starting…")
                .setCancelable(false)
                .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val files = RommGameFiles(context.applicationContext)
            val failed = mutableListOf<String>()
            games.forEachIndexed { i, g ->
                progress.setMessage("${i + 1} of ${games.size}: ${g.game.title} (${formatBytes(g.sizeBytes)})")
                val ok = withContext(Dispatchers.IO) { if (toUsb) files.moveToUsb(g.romId) else files.moveToTv(g.romId) }
                if (!ok) failed += g.game.title
            }
            progress.dismiss()
            if (failed.isNotEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("Some games weren't moved")
                    .setMessage("There wasn't enough space, or the drive was removed. They're still where they were:\n\n" + failed.joinToString("\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
            refreshInstalled()
        }
    }

    // ---- Settings

    @Composable
    private fun Settings() {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingTile("Sync library", if (home.indexInProgress) "Syncing now…" else syncSummary()) {
                    LibraryIndexScheduler.scheduleLibrarySync(requireContext().applicationContext)
                    Toast.makeText(requireContext(), "Syncing your library with RomM…", Toast.LENGTH_SHORT).show()
                }
                SettingTile("RomM account", "Connection check and sign in") { showRommStatus() }
                SettingTile("Download location", "This ${com.swordfish.lemuroid.lib.romm.Device.word} or a USB drive") { showDownloadLocation() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingTile("Check for updates", "Build ${com.swordfish.lemuroid.BuildConfig.VERSION_CODE} installed") {
                    Toast.makeText(requireContext(), "Checking for a newer build…", Toast.LENGTH_SHORT).show()
                    AppUpdater.checkInBackground(requireActivity(), viewLifecycleOwner.lifecycleScope)
                }
                SettingTile(
                    "RetroAchievements",
                    com.swordfish.lemuroid.app.tv.romm.RetroAchievementsAccount.load(requireContext())?.takeIf { resumeCount >= 0 }?.let {
                        if (it.enabled) "Earning as ${it.username}" else "Off (signed in as ${it.username})"
                    } ?: "Sign in to earn achievements while you play",
                ) {
                    startActivity(Intent(requireContext(), com.swordfish.lemuroid.app.tv.romm.RetroAchievementsActivity::class.java))
                }
                SettingTile("Controller buttons", "A button layout for each console") {
                    com.swordfish.lemuroid.app.tv.romm.ConsoleControlsActivity.open(
                        requireContext(),
                        home.metaSystems.flatMap { it.metaSystem.systemIDs },
                    )
                }
                SettingTile("Emulator settings", "Controllers, display, cores and saves") {
                    startActivity(Intent(requireContext(), TVSettingsActivity::class.java))
                }
            }
            if (!com.swordfish.lemuroid.lib.romm.Device.isTv(requireContext())) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingTile(
                        "Touch controls vibration: " +
                            when (touchVibration) {
                                "none" -> "Off"
                                "press_release" -> "Press and release"
                                else -> "On press"
                            },
                        "Tap to change. The on-screen buttons hide while a controller is connected.",
                    ) {
                        touchVibration =
                            when (touchVibration) {
                                "press" -> "press_release"
                                "press_release" -> "none"
                                else -> "press"
                            }
                        emulatorPrefs().edit().putString(getString(R.string.pref_key_haptic_feedback_mode), touchVibration).apply()
                    }
                    Spacer(Modifier.weight(2f))
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.SettingTile(
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ) {
        FocusBox(Modifier.weight(1f).height(92.dp), onClick = onClick) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = TextColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Quiet, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }

    /** A connection report (including a cover art test), and a way to sign in again. */
    private fun showRommStatus() {
        val context = requireContext()
        val dialog =
            AlertDialog.Builder(context)
                .setTitle("RomM account")
                .setMessage("Checking your RomM server…")
                .setPositiveButton("Close", null)
                .setNeutralButton("Sign in again") { _, _ -> startActivity(Intent(context, RommSignInActivity::class.java)) }
                .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val report =
                withContext(Dispatchers.IO) {
                    val db =
                        runCatching {
                            retrogradeDb.query("SELECT COUNT(*), SUM(coverFrontUrl IS NOT NULL) FROM games", null).use {
                                it.moveToFirst()
                                "In the app: ${it.getInt(0)} games, ${it.getInt(1)} with cover art"
                            }
                        }.getOrElse { "In the app: couldn't read (${it.message})" }
                    RommDiagnostics.report(context.applicationContext) + "\n\n" + db
                }
            dialog.setMessage(report)
        }
    }

    // ---- shared pieces

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text,
            color = Quiet,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 48.dp, top = 10.dp),
        )
    }

    /** A game: cover on top, name and system below. The name area is green when the game is downloaded. */
    @Composable
    private fun GameTile(
        game: Game,
        modifier: Modifier = Modifier,
        fill: Boolean = false,
        subtitle: String? = null,
        onClick: () -> Unit = { gameInteractor.onGamePlay(game) },
    ) {
        val ready = isInstalled(game)
        val size = if (fill) Modifier.fillMaxWidth() else Modifier.width(TILE_WIDTH)
        FocusBox(modifier.then(size), onClick = onClick) {
            Column {
                Cover(game, Modifier.fillMaxWidth().height(TILE_COVER_HEIGHT))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(TILE_INFO_HEIGHT)
                            .background(if (ready) Green else Panel)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        game.title,
                        color = TextColor,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle ?: GameUtils.getGameSubtitle(requireContext(), game),
                        color = if (ready) Color(0xFFD9F5C8) else Quiet,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun Cover(
        game: Game,
        modifier: Modifier,
    ) {
        val url = game.coverFrontUrl
        if (url.isNullOrBlank()) {
            Box(modifier.background(Raised), contentAlignment = Alignment.Center) {
                Text(
                    game.title,
                    color = Quiet,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.background(Raised),
            )
        }
    }

    @Composable
    private fun TabButton(
        text: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(7.dp)
        Box(
            modifier
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(4.dp)
                .clip(shape)
                .background(if (selected) Lime else if (focused) Raised else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                text,
                color = if (selected) Ground else if (focused) TextColor else Quiet,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }

    /** A card that shows a lime outline while the controller is on it. */
    @Composable
    private fun FocusBox(
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(9.dp)
        Box(
            modifier
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Line, shape)
                .padding(2.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Panel)
                .clickable(onClick = onClick),
        ) {
            content()
        }
    }

    @dagger.Module
    class Module

    companion object {
        private val TILE_WIDTH: Dp = 118.dp
        private val TILE_COVER_HEIGHT: Dp = 152.dp
        private val TILE_INFO_HEIGHT: Dp = 60.dp

        private val Ground = Color(0xFF0D1117)
        private val Panel = Color(0xFF161C26)
        private val Raised = Color(0xFF1E2633)
        private val Line = Color(0xFF2A3444)
        private val TextColor = Color(0xFFEEF2F6)
        private val Quiet = Color(0xFFA7B2C2)
        private val Lime = Color(0xFF8FDC4E)
        private val Green = Color(0xFF107C10)
    }
}
