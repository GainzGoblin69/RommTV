package com.swordfish.lemuroid.app.tv.romm

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Your RetroAchievements profile: points, retro ratio, what you're doing now, recently played games, recent
 * unlocks and awards. Pick a game for its achievements (with how many players earned each), leaderboards,
 * comments and the game files RetroAchievements supports. Opened by selecting your picture at the top of the
 * home screen.
 */
class RaProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneUi.apply(this)
        val account = RetroAchievementsAccount.load(this)
        if (account == null) {
            finish()
            return
        }
        setContent { Root(account.username) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        UiSounds.onKey(this, event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A ->
                super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount))
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    @Composable
    private fun Root(user: String) {
        var key by remember { mutableStateOf(RaWeb.apiKey(this)) }
        var gameId by remember { mutableStateOf<Int?>(null) }
        BackHandler(enabled = gameId != null) { gameId = null }
        Box(Modifier.fillMaxSize().background(Ground)) {
            val k = key
            when {
                k == null -> KeySetup(user) { key = it }
                gameId != null -> GameScreen(k, user, gameId!!)
                else -> ProfileScreen(k, user, onKeyRejected = { key = null }) { gameId = it }
            }
        }
    }

    // ---- web API key

    @Composable
    private fun KeySetup(
        user: String,
        onSaved: (String) -> Unit,
    ) {
        var value by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        LaunchedEffect(checking) {
            if (!checking) return@LaunchedEffect
            val result = withContext(Dispatchers.IO) { runCatching { RaWeb.profile(value.trim(), user) } }
            checking = false
            if (result.isSuccess) {
                RaWeb.setApiKey(this@RaProfileActivity, value)
                onSaved(value.trim())
            } else {
                status = "That key didn't work: ${result.exceptionOrNull()?.message ?: "no answer"}. Check it and try again."
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Your RetroAchievements profile", color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "To show your stats, RetroAchievements needs your web API key (one time only). On retroachievements.org, " +
                    "sign in as $user, open Settings, and copy the key under \"Keys\" (Web API Key). Paste or type it here.",
                color = Quiet,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = { value = it.trim() },
                singleLine = true,
                textStyle = TextStyle(color = TextColor, fontSize = 17.sp),
                cursorBrush = SolidColor(Lime),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(first)
                        .onFocusChanged { focused = it.isFocused }
                        .border(2.dp, if (focused) Lime else Line, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Raised)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
            )
            status?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(if (checking) "Checking…" else "Save", highlight = true) { if (value.isNotBlank() && !checking) checking = true }
                Chip("Cancel") { finish() }
            }
        }
    }

    // ---- profile

    private class ProfileData(
        val profile: RaWeb.Profile,
        val played: List<RaWeb.PlayedGame>,
    )

    @Composable
    private fun ProfileScreen(
        key: String,
        user: String,
        onKeyRejected: () -> Unit,
        onGame: (Int) -> Unit,
    ) {
        var data by remember { mutableStateOf<ProfileData?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var tab by remember { mutableStateOf(0) }
        var unlocks by remember { mutableStateOf<List<RaWeb.Unlock>?>(null) }
        var awards by remember { mutableStateOf<RaWeb.Awards?>(null) }
        val firstTab = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { ProfileData(RaWeb.profile(key, user), RaWeb.recentlyPlayed(key, user)) }
                }
            result.onSuccess { data = it }.onFailure {
                if (it is RaWeb.ApiException && it.message?.contains("key") == true) {
                    RaWeb.setApiKey(this@RaProfileActivity, null)
                    onKeyRejected()
                } else {
                    error = "Couldn't load your profile: ${it.message ?: "no connection"}"
                }
            }
        }
        LaunchedEffect(tab) {
            if (tab == 1 && unlocks == null) unlocks = withContext(Dispatchers.IO) { runCatching { RaWeb.recentUnlocks(key, user) }.getOrDefault(emptyList()) }
            if (tab == 2 && awards == null) awards = withContext(Dispatchers.IO) { runCatching { RaWeb.awards(key, user) }.getOrNull() }
        }
        LaunchedEffect(data != null) { if (data != null) runCatching { firstTab.requestFocus() } }

        val d = data
        if (d == null) {
            Message(error ?: "Loading your RetroAchievements profile…")
            return
        }
        val p = d.profile
        val lastGame = d.played.firstOrNull { it.id == p.lastGameId } ?: d.played.firstOrNull()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item {
                Header(lastGame?.ingameUrl) {
                    AsyncImage(
                        model = p.picUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.user, color = TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        if (p.memberSince.isNotBlank()) Text("Joined ${RaWeb.prettyDate(p.memberSince)}", color = Quiet, fontSize = 13.sp)
                        if (p.motto.isNotBlank()) Text(p.motto, color = TextColor, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Stat("${p.hardcorePoints} Hardcore")
                            Stat("${p.softcorePoints} Softcore")
                            Stat("%.2f Retro Ratio".format(p.retroRatio))
                        }
                    }
                }
            }
            if (p.richPresence.isNotBlank()) {
                item {
                    Row(
                        Modifier
                            .padding(horizontal = 48.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Panel)
                            .border(1.dp, Line, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        lastGame?.let {
                            AsyncImage(it.iconUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(14.dp))
                        }
                        Column {
                            lastGame?.let { Text(it.title, color = Quiet, fontSize = 12.sp) }
                            Text(p.richPresence, color = Lime, fontSize = 16.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
            item {
                Tabs(listOf("Recently played", "Recent unlocks", "Awards"), tab, firstTab) { tab = it }
            }
            when (tab) {
                0 -> playedRows(d.played, onGame)
                1 -> unlockRows(unlocks, onGame)
                else -> awardRows(awards, onGame)
            }
        }
    }

    private fun LazyListScope.playedRows(
        games: List<RaWeb.PlayedGame>,
        onGame: (Int) -> Unit,
    ) {
        if (games.isEmpty()) item { Note("No games played yet.") }
        items(games, key = { "g" + it.id }) { g ->
            Entry(onClick = { onGame(g.id) }) {
                AsyncImage(g.iconUrl, null, Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(g.title, color = TextColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(g.console, color = Quiet, fontSize = 13.sp)
                    if (g.lastPlayed.isNotBlank()) Text("Last played ${RaWeb.prettyDate(g.lastPlayed)}", color = Quiet, fontSize = 13.sp)
                    if (g.possible > 0) {
                        Progress(g.achieved.toFloat() / g.possible)
                        Text(
                            "${g.achieved}/${g.possible} · ${"%.2f".format(100f * g.achieved / g.possible)}%",
                            color = TextColor,
                            fontSize = 13.sp,
                        )
                    } else {
                        Text("No achievements for this game", color = Quiet, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    private fun LazyListScope.unlockRows(
        unlocks: List<RaWeb.Unlock>?,
        onGame: (Int) -> Unit,
    ) {
        when {
            unlocks == null -> item { Note("Loading…") }
            unlocks.isEmpty() -> item { Note("No achievements unlocked in the last 30 days.") }
            else ->
                items(unlocks) { u ->
                    Entry(onClick = { onGame(u.gameId) }) {
                        AsyncImage(u.badgeUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(u.title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(u.description, color = Quiet, fontSize = 13.sp, lineHeight = 17.sp)
                            Text(
                                "${u.gameTitle} · ${u.points} points · ${RaWeb.prettyDate(u.date)}" + if (u.hardcore) " · Hardcore" else "",
                                color = Lime,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
        }
    }

    private fun LazyListScope.awardRows(
        awards: RaWeb.Awards?,
        onGame: (Int) -> Unit,
    ) {
        if (awards == null) {
            item { Note("Loading…") }
            return
        }
        item {
            Row(Modifier.padding(horizontal = 48.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${awards.total} awards")
                Stat("${awards.mastered} mastered")
                Stat("${awards.completed} completed")
                Stat("${awards.beaten} beaten")
            }
        }
        if (awards.list.isEmpty()) item { Note("No awards yet. Beat or finish every achievement in a game to earn one.") }
        items(awards.list) { a ->
            Entry(onClick = { if (a.gameId > 0) onGame(a.gameId) }) {
                AsyncImage(a.iconUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(a.title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(a.console, color = Quiet, fontSize = 13.sp)
                    Text("${a.kind} · ${RaWeb.prettyDate(a.date)}", color = Lime, fontSize = 13.sp)
                }
            }
        }
    }

    // ---- one game

    @Composable
    private fun GameScreen(
        key: String,
        user: String,
        gameId: Int,
    ) {
        var game by remember(gameId) { mutableStateOf<RaWeb.GameProgress?>(null) }
        var error by remember(gameId) { mutableStateOf<String?>(null) }
        var tab by remember(gameId) { mutableStateOf(0) }
        var boards by remember(gameId) { mutableStateOf<List<RaWeb.Leaderboard>?>(null) }
        var comments by remember(gameId) { mutableStateOf<List<RaWeb.Comment>?>(null) }
        var files by remember(gameId) { mutableStateOf<List<RaWeb.FileHash>?>(null) }
        val firstTab = remember { FocusRequester() }

        LaunchedEffect(gameId) {
            withContext(Dispatchers.IO) { runCatching { RaWeb.game(key, user, gameId) } }
                .onSuccess { game = it }
                .onFailure { error = "Couldn't load this game: ${it.message ?: "no connection"}" }
        }
        LaunchedEffect(gameId, tab) {
            when (tab) {
                1 -> if (boards == null) boards = withContext(Dispatchers.IO) { runCatching { RaWeb.leaderboards(key, gameId) }.getOrDefault(emptyList()) }
                2 -> if (comments == null) comments = withContext(Dispatchers.IO) { runCatching { RaWeb.comments(key, gameId) }.getOrDefault(emptyList()) }
                3 -> if (files == null) files = withContext(Dispatchers.IO) { runCatching { RaWeb.hashes(key, gameId) }.getOrDefault(emptyList()) }
            }
        }
        LaunchedEffect(game != null) { if (game != null) runCatching { firstTab.requestFocus() } }

        val g = game
        if (g == null) {
            Message(error ?: "Loading…")
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item {
                Header(g.ingameUrl) {
                    AsyncImage(g.iconUrl, null, Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(g.title, color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(g.console, color = Quiet, fontSize = 14.sp)
                        if (g.achievements.isNotEmpty()) Progress(g.earned.toFloat() / g.achievements.size)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Stat("${g.earned}/${g.achievements.size} Achievements")
                            Stat("${g.earnedPoints}/${g.points} Points")
                            if (g.players > 0) Stat("${g.players} players")
                        }
                    }
                }
            }
            item { Tabs(listOf("Achievements", "Leaderboards", "Comments", "Supported files"), tab, firstTab) { tab = it } }
            when (tab) {
                0 -> {
                    if (g.achievements.isEmpty()) item { Note("This game has no achievements yet.") }
                    items(g.achievements) { a ->
                        Entry(onClick = {}) {
                            AsyncImage(
                                if (a.earned != null) a.badgeUrl else a.lockedBadgeUrl,
                                null,
                                Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                                colorFilter = if (a.earned != null) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(a.title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(a.description, color = Quiet, fontSize = 13.sp, lineHeight = 17.sp)
                                Text("${a.points} points (${a.trueRatio})", color = TextColor, fontSize = 13.sp)
                                if (g.players > 0) {
                                    Text("${a.awarded} (${a.awardedHardcore}) of ${g.players} players", color = Quiet, fontSize = 12.sp)
                                }
                            }
                            if (a.earned != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("✓", color = Lime, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                    Text(RaWeb.prettyDate(a.earned), color = Lime, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                1 ->
                    when {
                        boards == null -> item { Note("Loading…") }
                        boards!!.isEmpty() -> item { Note("This game has no leaderboards.") }
                        else ->
                            items(boards!!) { b ->
                                Entry(onClick = {}) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(b.title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text(b.description, color = Quiet, fontSize = 13.sp)
                                        Text(
                                            "Format: ${b.format} · ${if (b.lowerIsBetter) "Lower" else "Higher"} is better",
                                            color = Quiet,
                                            fontSize = 12.sp,
                                        )
                                        b.top?.let { Text("Top: $it", color = Lime, fontSize = 13.sp) }
                                    }
                                }
                            }
                    }
                2 ->
                    when {
                        comments == null -> item { Note("Loading…") }
                        comments!!.isEmpty() -> item { Note("No comments yet.") }
                        else ->
                            items(comments!!) { c ->
                                Entry(onClick = {}) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(c.user, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text(RaWeb.prettyDate(c.date), color = Quiet, fontSize = 12.sp)
                                        Text(c.text, color = TextColor, fontSize = 14.sp, lineHeight = 19.sp)
                                    }
                                }
                            }
                    }
                else ->
                    when {
                        files == null -> item { Note("Loading…") }
                        files!!.isEmpty() -> item { Note("No supported files listed.") }
                        else ->
                            items(files!!) { f ->
                                Entry(onClick = {}) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(f.name, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text("MD5: ${f.md5}", color = Quiet, fontSize = 13.sp)
                                        if (f.labels.isNotEmpty()) Text("Labels: ${f.labels.joinToString()}", color = Quiet, fontSize = 13.sp)
                                    }
                                }
                            }
                    }
            }
        }
    }

    // ---- pieces

    /** A banner of the game's in-game picture, dimmed, with the header content over it. */
    @Composable
    private fun Header(
        banner: String?,
        content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    ) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            if (!banner.isNullOrBlank()) {
                AsyncImage(banner, null, Modifier.fillMaxSize().alpha(0.45f), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ground.copy(alpha = 0.3f), 1f to Ground)))
            Row(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }

    @Composable
    private fun Tabs(
        names: List<String>,
        selected: Int,
        first: FocusRequester,
        onSelect: (Int) -> Unit,
    ) {
        Row(Modifier.padding(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, name ->
                Chip(name, highlight = i == selected, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onSelect(i) }
            }
        }
    }

    @Composable
    private fun Entry(
        onClick: () -> Unit,
        content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        Row(
            Modifier
                .padding(horizontal = 48.dp, vertical = 4.dp)
                .fillMaxWidth()
                .focusSound()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Line, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .tapClickable(onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }

    @Composable
    private fun Chip(
        text: String,
        highlight: Boolean = false,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier
                .focusSound()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (highlight) Green else Raised)
                .tapClickable(onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(text, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }

    @Composable
    private fun Stat(text: String) {
        Text(
            text,
            color = TextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Ground.copy(alpha = 0.7f))
                    .border(1.dp, Line, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    @Composable
    private fun Progress(fraction: Float) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Raised)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Lime))
        }
    }

    @Composable
    private fun Note(text: String) {
        Text(text, color = Quiet, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp))
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = Quiet, fontSize = 16.sp)
        }
    }

    private companion object {
        val Ground = RommSignInActivity.Ground
        val Panel = RommSignInActivity.Panel
        val Line = RommSignInActivity.Line
        val TextColor = RommSignInActivity.TextColor
        val Quiet = RommSignInActivity.Quiet
        val Lime = RommSignInActivity.Lime
        val Green = RommSignInActivity.Green
        val Raised = Color(0xFF1E2633)
    }
}
