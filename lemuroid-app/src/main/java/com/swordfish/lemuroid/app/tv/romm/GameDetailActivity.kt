package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.GameLauncher
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.app.tv.channel.ChannelUpdateWork
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.romm.RommAchievement
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommGameFiles
import com.swordfish.lemuroid.lib.romm.RommRomDetail
import com.swordfish.lemuroid.lib.romm.RommSave
import com.swordfish.lemuroid.lib.romm.RommState
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.romm.RommSession
import dagger.android.AndroidInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * The page that opens when a game is chosen: what RomM knows about it (summary, time to beat,
 * RetroAchievements, screenshots), Play, and a picker for which RomM save to start from.
 */
@OptIn(ExperimentalFoundationApi::class)
class GameDetailActivity : ComponentActivity() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameLauncher: GameLauncher

    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var coresSelection: CoresSelection

    private val sync by lazy { RommSync(this, statesManager) }

    /** When the game was started, for reporting play time to RomM. */
    private var launchedAt = 0L

    private lateinit var client: RommClient

    private var game by mutableStateOf<Game?>(null)
    private var detail by mutableStateOf<RommRomDetail?>(null)
    private var earned by mutableStateOf<Map<String, String>>(emptyMap())
    private var failedShots by mutableStateOf<Set<String>>(emptySet())
    private var earnedFirst by mutableStateOf(false)
    private var shownAchievement by mutableStateOf<RommAchievement?>(null)
    private var onUsb by mutableStateOf(false)
    private var openRooms by mutableStateOf(0)
    private var status by mutableStateOf("Loading from RomM…")
    private var showSaves by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var onTv by mutableStateOf(false)
    private var favorite by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        client = RommClient(RommSession(this))
        setContent { Screen() }
        load(intent.getIntExtra(EXTRA_GAME_ID, -1))
    }

    override fun onResume() {
        super.onResume()
        refreshOnTv()
    }

    private fun refreshOnTv() {
        val romId = game?.let { RommSync.romId(it) } ?: return
        val files = RommGameFiles(this)
        onTv = files.isDownloaded(romId)
        onUsb = onTv && files.isOnUsb(romId)
    }

    private fun load(gameId: Int) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { retrogradeDb.gameDao().selectById(gameId) }
            if (loaded == null) {
                finish()
                return@launch
            }
            game = loaded
            favorite = loaded.isFavorite
            refreshOnTv()
            refreshDetail()
        }
    }

    private suspend fun refreshDetail() {
        val romId = game?.let { RommSync.romId(it) } ?: return
        val result = withContext(Dispatchers.IO) { runCatching { client.romDetail(romId) } }
        result.onSuccess {
            detail = it
            if (status.startsWith("Loading")) status = ""
        }.onFailure {
            Timber.e(it, "Couldn't load game details")
            status = "Couldn't load details from RomM: ${it.message}"
        }
        openRooms = withContext(Dispatchers.IO) { runCatching { client.netplayRooms(romId) }.getOrDefault(0) }
        val raId = detail?.raId ?: return
        if (detail?.achievements.isNullOrEmpty()) return
        earned = withContext(Dispatchers.IO) { runCatching { client.earnedAchievements(raId) }.getOrDefault(emptyMap()) }
    }

    private fun startGame(
        current: Game,
        loadSave: Boolean,
    ) {
        launchedAt = System.currentTimeMillis()
        gameLauncher.launchGameAsync(this, current, loadSave, true)
    }

    /**
     * Play: first ask RomM whether the TV's save is current. A newer RomM save (from the website or another
     * device) is downloaded and the game starts from it; a newer TV save is uploaded; if both changed,
     * the player chooses. Without a connection the game just starts from the TV's save.
     */
    private fun play(loadSave: Boolean) {
        val current = game ?: return
        val info = detail
        if (busy) return
        showSaves = false
        if (info == null) {
            startGame(current, loadSave)
            return
        }
        busy = true
        status = "Syncing your save with RomM…"
        lifecycleScope.launch {
            val before = withContext(Dispatchers.IO) { runCatching { sync.beforePlay(current, info) } }
            val result =
                before.getOrElse {
                    Timber.e(it, "Sync before play failed")
                    busy = false
                    status = syncProblem(it)
                    startGame(current, loadSave)
                    return@launch
                }
            var downloaded = result.downloaded
            var completed = result.completed
            var failed = result.failed
            if (result.conflict != null) {
                val useRomm = askConflict(result)
                val resolved = withContext(Dispatchers.IO) { runCatching { sync.resolveConflict(current, result, useRomm) } }
                resolved.onSuccess {
                    downloaded = it
                    completed++
                }.onFailure {
                    failed++
                    status = "Couldn't finish syncing: ${it.message}. Playing the TV's save."
                }
            }
            withContext(Dispatchers.IO) { sync.finishBeforePlay(result, completed, failed) }
            busy = false
            status = result.message ?: if (downloaded) "Loaded your RomM save." else ""
            // A save that just came from RomM is newer than the TV's last position, so start the game from it
            startGame(current, loadSave && !downloaded)
        }
    }

    /** RomM and the TV both changed the save since they last agreed: the player picks one. */
    private suspend fun askConflict(before: RommSync.BeforePlay): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val tvTime = before.local?.updatedAtMillis?.let { java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(it) } ?: "unknown"
            val rommTime = GameDates.dateTime(before.conflict?.serverUpdatedAt)
            var answered = false
            android.app.AlertDialog.Builder(this)
                .setTitle("Which save do you want?")
                .setMessage(
                    "This game's save changed on the TV and in RomM since they last matched.\n\n" +
                        "TV save: $tvTime\nRomM save: $rommTime\n\n" +
                        "The one you don't pick is kept in RomM's save history.",
                )
                .setPositiveButton("Use RomM save") { _, _ ->
                    answered = true
                    cont.resumeWith(Result.success(true))
                }
                .setNegativeButton("Keep TV save") { _, _ ->
                    answered = true
                    cont.resumeWith(Result.success(false))
                }
                .setOnDismissListener { if (!answered) cont.resumeWith(Result.success(false)) }
                .show()
        }

    private fun syncProblem(e: Throwable): String =
        if ((e as? com.swordfish.lemuroid.lib.romm.RommException)?.code == 400) {
            "Sync is turned off for this TV in RomM (Devices). Playing the TV's save."
        } else {
            "Couldn't sync with RomM (${e.message}). Playing the TV's save."
        }

    private fun playWithSave(save: RommSave) {
        val current = game ?: return
        if (busy) return
        busy = true
        showSaves = false
        status = "Getting the save from RomM…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { sync.useServerSave(current, save) } }
            busy = false
            result.onSuccess {
                status = ""
                // Start from the save itself, not from where the TV last left off
                startGame(current, false)
            }.onFailure {
                Timber.e(it, "Couldn't fetch save")
                status = "Couldn't get that save from RomM: ${it.message}"
            }
        }
    }

    /** A RomM save state: it becomes the TV's resume point, and the game starts there. */
    private fun playWithState(state: RommState) {
        val current = game ?: return
        if (busy) return
        busy = true
        showSaves = false
        status = "Getting the save state from RomM…"
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { sync.useServerState(current, coreFor(current), state) }
                }
            busy = false
            result.onSuccess {
                status = ""
                startGame(current, true)
            }.onFailure {
                Timber.e(it, "Couldn't fetch state")
                status = "Couldn't get that save state from RomM: ${it.message}"
            }
        }
    }

    private suspend fun coreFor(game: Game): CoreID =
        coresSelection.getCoreConfigForSystem(GameSystem.findById(game.systemId)).coreID

    private fun toggleFavorite() {
        val current = game ?: return
        favorite = !favorite
        val updated = current.copy(isFavorite = favorite)
        game = updated
        lifecycleScope.launch(Dispatchers.IO) { retrogradeDb.gameDao().update(updated) }
    }

    private fun removeFromTv() {
        val romId = game?.let { RommSync.romId(it) } ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { RommGameFiles(applicationContext).remove(romId) }
            onTv = false
            status = "Removed from this TV. It downloads again the next time you play. Your saves are kept."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != BaseGameActivity.REQUEST_PLAY_GAME) return
        lifecycleScope.launch {
            runCatching { gameLaunchTaskHandler.handleGameFinish(false, this@GameDetailActivity, resultCode, data) }
            ChannelUpdateWork.enqueue(applicationContext)
            val current = game ?: return@launch
            val info = detail
            if (resultCode == Activity.RESULT_OK && info != null) {
                // Sync the save and states, and report how long you played
                val end = System.currentTimeMillis()
                val duration =
                    data?.getLongExtra(BaseGameActivity.PLAY_GAME_RESULT_SESSION_DURATION, 0L)?.takeIf { it > 0 }
                        ?: (end - launchedAt)
                val start = if (launchedAt > 0) launchedAt else end - duration
                status = "Syncing with RomM…"
                val note =
                    withContext(Dispatchers.IO) {
                        runCatching { sync.afterPlay(current, info, coreFor(current), start, end, duration) }
                            .getOrElse {
                                Timber.e(it, "Sync after play failed")
                                "Couldn't sync with RomM after playing: ${it.message}"
                            }
                    }
                status = note ?: ""
            }
            refreshOnTv()
            refreshDetail()
        }
    }

    /** A game controller's A button presses the focused item, and B goes back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A ->
                super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount))
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) finish()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    // ---- the page

    @Composable
    private fun Screen() {
        val current = game
        val info = detail
        val playFocus = remember { FocusRequester() }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        // Selecting anything in the top section asks the page to show the whole section (from the title down to
        // the selected button), not just the button. At the top that means no scrolling at all; coming up from
        // the achievements it means one scroll back to the top. If the section is taller than the screen, the
        // usual "just show the button" applies, so the buttons never end up off screen.
        val screenHeightPx = with(LocalDensity.current) { (LocalConfiguration.current.screenHeightDp.dp - 56.dp).toPx() }
        var headerWidth by remember { mutableStateOf(0f) }
        val headerResponder =
            remember(screenHeightPx) {
                object : BringIntoViewResponder {
                    override fun calculateRectForParent(localRect: Rect): Rect =
                        if (localRect.bottom <= screenHeightPx) Rect(0f, 0f, headerWidth, localRect.bottom) else localRect

                    override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
                }
            }
        LaunchedEffect(current != null) {
            if (current != null) runCatching { playFocus.requestFocus() }
        }

        Box(Modifier.fillMaxSize().background(Ground)) {
            if (current == null) return@Box
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .onSizeChanged { headerWidth = it.width.toFloat() }
                            .bringIntoViewResponder(headerResponder),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    RommImage(
                        urls = listOfNotNull(current.coverFrontUrl),
                        description = "Cover",
                        modifier = Modifier.width(190.dp).height(254.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            (info?.platformName ?: "").uppercase(),
                            color = Lime,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp,
                        )
                        Text(
                            info?.title ?: current.title,
                            color = TextColor,
                            fontSize = 32.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (info != null) {
                            val meta =
                                listOfNotNull(
                                    info.releaseDate?.let { year(it) },
                                    info.genres.take(3).joinToString(", ").ifBlank { null },
                                    info.companies.firstOrNull(),
                                    info.playerCount?.takeIf { it.isNotBlank() }?.let { "Players: $it" },
                                    info.regions.joinToString(", ").ifBlank { null },
                                ).joinToString("   ·   ")
                            if (meta.isNotBlank()) Text(meta, color = Quiet, fontSize = 13.sp)
                            Text(
                                info.summary?.let { clean(it) } ?: "RomM has no description for this game.",
                                color = Color(0xFFC9D2DD),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TvButton("Play", primary = true, modifier = Modifier.focusRequester(playFocus)) { play(true) }
                            TvButton("Pick a save" + (info?.saves?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: "")) { showSaves = true }
                            if (info != null && supportsMultiplayer(info)) {
                                TvButton(
                                    "Play online" + (if (openRooms > 0) " ($openRooms room" + (if (openRooms == 1) "" else "s") + " open)" else ""),
                                ) { NetplayActivity.launch(this@GameDetailActivity, info.id) }
                            }
                            TvButton(if (favorite) "★ Favorite" else "☆ Favorite") { toggleFavorite() }
                            if (onTv) TvButton("Remove from this TV") { removeFromTv() }
                        }
                        if (status.isNotBlank()) Text(status, color = Lime, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }

                if (info != null) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("TIME TO BEAT", timeToBeat(info), "From HowLongToBeat", Modifier.weight(1f).fillMaxHeight())
                        StatCard(
                            "ACHIEVEMENTS",
                            if (info.achievements.isEmpty()) "None for this game" else "${earnedCount(info)} of ${info.achievements.size} earned",
                            "RetroAchievements",
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                        StatCard(
                            "SAVES IN ROMM",
                            if (info.saves.isEmpty()) "None yet" else "${info.saves.size} save" + (if (info.saves.size == 1) "" else "s"),
                            info.lastPlayed?.let { "Last played ${date(it)}" } ?: "Not played yet",
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                        StatCard(
                            "ON THIS TV",
                            if (onUsb) "On the USB drive" else if (onTv) "Downloaded" else "Downloads when you play",
                            size(info.sizeBytes),
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    // Screenshots the server can't deliver are left out instead of showing empty boxes
                    val shots = info.screenshots.filter { it !in failedShots }
                    if (shots.isNotEmpty()) {
                        SectionTitle("SCREENSHOTS")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shots) { path ->
                                FocusCard(Modifier.width(264.dp).height(150.dp)) {
                                    RommImage(client.imageCandidates(path), "Screenshot", Modifier.fillMaxSize()) {
                                        failedShots = failedShots + path
                                    }
                                }
                            }
                        }
                    }

                    if (info.achievements.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("ACHIEVEMENTS  ·  ${earnedCount(info)} / ${info.achievements.size}")
                            TvButton(if (earnedFirst) "Sort: Earned first" else "Sort: Game order") { earnedFirst = !earnedFirst }
                        }
                        val ordered = if (earnedFirst) info.achievements.sortedByDescending { isEarned(it) } else info.achievements
                        // A grid that scrolls down with the page, three across
                        ordered.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                rowItems.forEach { a ->
                                    AchievementCard(a, isEarned(a), Modifier.weight(1f).fillMaxHeight()) { shownAchievement = a }
                                }
                                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (showSaves && current != null) SavePicker(info?.saves.orEmpty(), info?.states.orEmpty(), info?.id)
        shownAchievement?.let { AchievementDetail(it) }
    }

    /** RomM descriptions sometimes carry line breaks as stray "rn" letters; turn them back into paragraphs. */
    private fun clean(text: String): String =
        text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n")
            .replace(Regex("(?<=[.!?\"”)])(?:rn)+(?=[A-Z\"“(])"), "\n\n")
            .trim()

    /** One achievement in full: large badge, whether it's earned and when, points, and the whole description. */
    @Composable
    private fun AchievementDetail(a: RommAchievement) {
        val close = remember { FocusRequester() }
        LaunchedEffect(a) { runCatching { close.requestFocus() } }
        val isEarned = isEarned(a)
        Dialog(onDismissRequest = { shownAchievement = null }) {
            Column(
                modifier =
                    Modifier
                        .width(560.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RommImage(badgeUrls(a, isEarned), null, Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(a.title, color = TextColor, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (isEarned) {
                                "Earned" + (a.badgeId?.let { earned[it] }?.let { date(it) }?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: "")
                            } else {
                                "Locked"
                            },
                            color = if (isEarned) Lime else Quiet,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            listOfNotNull(
                                "${a.points} points",
                                a.type?.takeIf { it.isNotBlank() }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() },
                                a.numAwarded.takeIf { it > 0 }?.let { "Earned by $it players" },
                            ).joinToString("   ·   "),
                            color = Quiet,
                            fontSize = 13.sp,
                        )
                    }
                }
                Text(a.description, color = Color(0xFFC9D2DD), fontSize = 15.sp, lineHeight = 21.sp)
                TvButton("Close", modifier = Modifier.focusRequester(close)) { shownAchievement = null }
            }
        }
    }

    private fun badgeUrls(
        a: RommAchievement,
        isEarned: Boolean,
    ): List<String> =
        if (isEarned) {
            listOfNotNull(a.badgeUrl) + client.imageCandidates(a.badgePath)
        } else {
            listOfNotNull(a.badgeUrlLock) + client.imageCandidates(a.badgePathLock) + listOfNotNull(a.badgeUrl)
        }

    /** Multiplayer when RomM says so; shown too when RomM has no player information for the game. */
    private fun supportsMultiplayer(info: RommRomDetail): Boolean {
        val maxPlayers = Regex("\\d+").findAll(info.playerCount ?: "").mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 0
        val modes = info.gameModes.map { it.lowercase() }
        if (maxPlayers > 1 || modes.any { "multi" in it || "co-op" in it || "coop" in it || "split" in it || "versus" in it }) return true
        return modes.isEmpty() && maxPlayers == 0
    }

    private fun isEarned(a: RommAchievement): Boolean = a.badgeId?.let { earned.containsKey(it) } ?: false

    private fun earnedCount(info: RommRomDetail) = info.achievements.count { isEarned(it) }

    @Composable
    private fun SavePicker(
        saves: List<RommSave>,
        states: List<RommState>,
        romId: Int?,
    ) {
        val fromTv = remember(romId) { romId?.let { sync.uploadedStateIds(it) } ?: emptySet() }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Dialog(onDismissRequest = { showSaves = false }) {
            Column(
                modifier =
                    Modifier
                        .width(560.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Pick a save", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SaveRow("Continue", "Syncs with RomM, then picks up where you left off", Modifier.focusRequester(first)) { play(true) }
                    if (saves.isNotEmpty()) PickerHeading("IN-GAME SAVES")
                    saves.forEach { save ->
                        SaveRow(
                            save.fileName,
                            listOfNotNull(
                                save.slot?.let { "Slot: $it" },
                                save.emulator,
                                date(save.updatedAt),
                                size(save.size),
                            ).joinToString("   ·   "),
                        ) { playWithSave(save) }
                    }
                    if (states.isNotEmpty()) PickerHeading("SAVE STATES")
                    states.forEach { state ->
                        SaveRow(
                            state.fileName,
                            listOfNotNull(
                                if (state.id in fromTv) "From this TV" else state.emulator?.let { "From the web player ($it)" },
                                date(state.updatedAt),
                                size(state.size),
                            ).joinToString("   ·   "),
                        ) { playWithState(state) }
                    }
                }
                Text(
                    if (saves.isEmpty() && states.isEmpty()) {
                        "Nothing in RomM for this game yet. Your saves and save states upload after you play."
                    } else {
                        "In-game saves replace this TV's save (the old one is kept as a backup). A save state starts the game " +
                            "exactly where it was made; states from the web player may not load if its emulator version differs."
                    },
                    color = Quiet,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    @Composable
    private fun PickerHeading(text: String) {
        Text(text, color = Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 6.dp))
    }

    @Composable
    private fun SaveRow(
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FocusCard(modifier.fillMaxWidth(), onClick = onClick) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(title, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Quiet, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun AchievementCard(
        a: RommAchievement,
        isEarned: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        FocusCard(modifier, onClick = onClick) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp).alpha(if (isEarned) 1f else 0.6f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RommImage(badgeUrls(a, isEarned), null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                Column {
                    Text(a.title, color = TextColor, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (isEarned) "Earned" else "Locked") + "  ·  ${a.points} pts",
                        color = if (isEarned) Lime else Quiet,
                        fontSize = 11.sp,
                    )
                    Text(a.description, color = Quiet, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, color = Quiet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    }

    @Composable
    private fun StatCard(
        label: String,
        value: String,
        note: String,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier =
                modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, color = Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Text(value, color = TextColor, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = Quiet, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }

    @Composable
    private fun TvButton(
        text: String,
        primary: Boolean = false,
        modifier: Modifier = Modifier,
        onFocused: () -> Unit = {},
        onClick: () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(8.dp)
        Box(
            modifier
                .onFocusChanged {
                    if (it.isFocused && !focused) onFocused()
                    focused = it.isFocused
                }
                .border(2.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(11.dp))
                .padding(4.dp)
                .clip(shape)
                .background(if (primary) Green else Raised)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(text, color = if (primary) Color.White else TextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    /** A card that shows a lime outline while the controller is on it. */
    @Composable
    private fun FocusCard(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Line, shape)
                .clip(shape)
                .background(Raised)
                .clickable(onClick = onClick),
        ) {
            content()
        }
    }

    /** Shows the first address that loads (the server keeps images under more than one file type). */
    @Composable
    private fun RommImage(
        urls: List<String>,
        description: String?,
        modifier: Modifier = Modifier,
        onFailed: () -> Unit = {},
    ) {
        var index by remember(urls) { mutableStateOf(0) }
        if (urls.isEmpty()) {
            Box(modifier.background(Raised))
            return
        }
        AsyncImage(
            model = urls[index.coerceAtMost(urls.lastIndex)],
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Raised),
            onError = { if (index < urls.lastIndex) index++ else onFailed() },
        )
    }

    // ---- wording

    private fun timeToBeat(info: RommRomDetail): String {
        val parts =
            listOfNotNull(
                info.hltbMain?.let { "Story ${hours(it)}" },
                info.hltbExtra?.let { "Extras ${hours(it)}" },
                info.hltbComplete?.let { "100% ${hours(it)}" },
            )
        return if (parts.isEmpty()) "Not known" else parts.joinToString("  ·  ")
    }

    private fun hours(seconds: Int): String {
        val h = Math.round(seconds / 3600.0 * 2) / 2.0
        return if (h == Math.floor(h)) "${h.toInt()} h" else "$h h"
    }

    private fun year(value: Long): String {
        val millis = if (value > 100_000_000_000L) value else value * 1000
        return Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR).toString()
    }

    private fun date(iso: String): String =
        runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val parsed = parser.parse(iso.take(19).replace(' ', 'T'))!!
            SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(parsed)
        }.getOrDefault("")

    private fun size(bytes: Long): String =
        when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
            bytes > 0 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
            else -> ""
        }

    companion object {
        private const val EXTRA_GAME_ID = "game_id"

        private val Ground = RommSignInActivity.Ground
        private val Panel = RommSignInActivity.Panel
        private val Raised = Color(0xFF1E2633)
        private val Line = RommSignInActivity.Line
        private val TextColor = RommSignInActivity.TextColor
        private val Quiet = RommSignInActivity.Quiet
        private val Lime = RommSignInActivity.Lime
        private val Green = RommSignInActivity.Green

        fun launch(
            context: Context,
            game: Game,
        ) {
            context.startActivity(Intent(context, GameDetailActivity::class.java).putExtra(EXTRA_GAME_ID, game.id))
        }
    }
}
