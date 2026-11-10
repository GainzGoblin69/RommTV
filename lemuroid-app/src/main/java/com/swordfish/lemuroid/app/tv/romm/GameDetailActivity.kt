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
class GameDetailActivity : ComponentActivity() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameLauncher: GameLauncher

    @Inject
    lateinit var gameLaunchTaskHandler: GameLaunchTaskHandler

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
        val romId = game?.let { RommSaves.romId(it) } ?: return
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
        val romId = game?.let { RommSaves.romId(it) } ?: return
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

    private fun play(loadSave: Boolean) {
        val current = game ?: return
        if (busy) return
        showSaves = false
        gameLauncher.launchGameAsync(this, current, loadSave, true)
    }

    private fun playWithSave(save: RommSave) {
        val current = game ?: return
        if (busy) return
        busy = true
        showSaves = false
        status = "Getting the save from RomM…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RommSaves.useServerSave(applicationContext, current, save) } }
            busy = false
            result.onSuccess {
                status = ""
                // Start from the save itself, not from where the TV last left off
                gameLauncher.launchGameAsync(this@GameDetailActivity, current, false, true)
            }.onFailure {
                Timber.e(it, "Couldn't fetch save")
                status = "Couldn't get that save from RomM: ${it.message}"
            }
        }
    }

    private fun toggleFavorite() {
        val current = game ?: return
        favorite = !favorite
        val updated = current.copy(isFavorite = favorite)
        game = updated
        lifecycleScope.launch(Dispatchers.IO) { retrogradeDb.gameDao().update(updated) }
    }

    private fun removeFromTv() {
        val romId = game?.let { RommSaves.romId(it) } ?: return
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
            if (resultCode == Activity.RESULT_OK) {
                val upload = withContext(Dispatchers.IO) { runCatching { RommSaves.uploadAfterPlay(applicationContext, current) } }
                upload.onSuccess { if (it) status = "Your save was uploaded to RomM (slot \"${RommClient.SAVE_SLOT}\")." }
                    .onFailure {
                        Timber.e(it, "Save upload failed")
                        status = "Your save is on this TV, but uploading it to RomM failed: ${it.message}"
                    }
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
        // Moving back up to the buttons brings the whole top of the page back, not just the button row
        val toTop: () -> Unit = { scope.launch { scroll.animateScrollTo(0) } }
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
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
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
                            TvButton("Play", primary = true, modifier = Modifier.focusRequester(playFocus), onFocused = toTop) { play(true) }
                            TvButton("Pick a save" + (info?.saves?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: ""), onFocused = toTop) { showSaves = true }
                            if (info != null && supportsMultiplayer(info)) {
                                TvButton(
                                    "Play online" + (if (openRooms > 0) " ($openRooms room" + (if (openRooms == 1) "" else "s") + " open)" else ""),
                                    onFocused = toTop,
                                ) { NetplayActivity.launch(this@GameDetailActivity, info.id) }
                            }
                            TvButton(if (favorite) "★ Favorite" else "☆ Favorite", onFocused = toTop) { toggleFavorite() }
                            if (onTv) TvButton("Remove from this TV", onFocused = toTop) { removeFromTv() }
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

        if (showSaves && current != null) SavePicker(info?.saves.orEmpty())
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
    private fun SavePicker(saves: List<RommSave>) {
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
                    SaveRow("Continue on this TV", "Picks up where you last stopped on this TV", Modifier.focusRequester(first)) { play(true) }
                    saves.forEach { save ->
                        SaveRow(
                            save.fileName,
                            listOfNotNull(save.slot?.let { "Slot: $it" }, date(save.updatedAt), size(save.size)).joinToString("   ·   "),
                        ) { playWithSave(save) }
                    }
                }
                Text(
                    if (saves.isEmpty()) {
                        "No saves in RomM for this game yet. Saves made on this TV upload after you play."
                    } else {
                        "A RomM save replaces this TV's save for the game. The TV's old save is kept as a backup file."
                    },
                    color = Quiet,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
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
