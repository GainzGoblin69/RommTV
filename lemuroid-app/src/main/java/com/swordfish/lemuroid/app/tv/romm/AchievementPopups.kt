package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.swordfish.libretrodroid.RetroAchievements
import kotlinx.coroutines.delay

/**
 * RetroAchievements pop-ups over the game: unlocks, the welcome line when a game is recognised, and any problem.
 * One at a time at the top of the screen, a few seconds each. They never take focus away from the game.
 */
class AchievementPopups(private val activity: Activity) : RetroAchievements.Listener {
    private data class Popup(
        val label: String,
        val title: String,
        val detail: String?,
        val imageUrl: String?,
        val highlight: Boolean,
        val seconds: Int,
    )

    private val queue = mutableStateListOf<Popup>()
    private var current by mutableStateOf<Popup?>(null)
    private var visible by mutableStateOf(false)
    private var warnedOffline = false

    fun attach() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val view =
            ComposeView(activity).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                setContent { Host() }
            }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        RetroAchievements.listener = this
    }

    fun detach() {
        if (RetroAchievements.listener === this) RetroAchievements.listener = null
    }

    private fun show(popup: Popup) {
        queue.add(popup)
    }

    override fun onGameLoaded(
        title: String,
        summary: String,
        achievementCount: Int,
        imageUrl: String?,
    ) {
        if (achievementCount == 0) {
            show(Popup("RETROACHIEVEMENTS", title, "This game has no achievements yet.", imageUrl, false, 4))
        } else {
            show(Popup("RETROACHIEVEMENTS", title, summary, imageUrl, false, 4))
        }
    }

    override fun onUnlocked(
        title: String,
        description: String,
        points: Int,
        badgeUrl: String?,
    ) {
        show(Popup("ACHIEVEMENT UNLOCKED · $points POINTS", title, description, badgeUrl, true, 6))
    }

    override fun onMastered(
        title: String,
        imageUrl: String?,
    ) {
        show(Popup("GAME MASTERED", title, "Every achievement earned. Nice.", imageUrl, true, 8))
    }

    override fun onProblem(
        kind: String,
        message: String?,
    ) {
        val text =
            when (kind) {
                "login_failed" -> "Couldn't sign in to RetroAchievements: ${message ?: "unknown error"}. Sign in again in Settings."
                "unknown_game" -> "RetroAchievements doesn't recognise this version of the game, so it has no achievements here."
                "no_memory" -> "Achievements don't work with this console's emulator yet."
                else -> "Achievements aren't available for this game: ${message ?: "unknown error"}"
            }
        show(Popup("RETROACHIEVEMENTS", "Achievements off for this game", text, null, false, 6))
    }

    override fun onEvent(
        kind: String,
        title: String?,
        description: String?,
        points: Int,
    ) {
        val c = activity.applicationContext
        when (kind) {
            "log" -> AchievementSession.log(c, description ?: "")
            "login_ok" -> AchievementSession.step(c, "Signed in as ${title ?: "you"}")
            "login_failed" -> AchievementSession.problem(c, "RetroAchievements sign-in failed: ${description ?: "unknown error"}")
            "game_info" ->
                if (points > 0) {
                    AchievementSession.step(c, "Game recognised (RetroAchievements #$points)")
                } else {
                    AchievementSession.problem(c, "RetroAchievements doesn't recognise this copy of the game (hash ${title ?: "?"})")
                }
            "game_loaded" -> AchievementSession.step(c, "$points achievements active · ${description ?: ""}")
            "unknown_game" -> AchievementSession.problem(c, "RetroAchievements doesn't recognise this copy of the game")
            "load_failed" -> AchievementSession.problem(c, "Couldn't load achievements: ${description ?: "unknown error"}")
            "memory" -> AchievementSession.step(c, "Watching ${points / 1024} KB of game memory (${description ?: ""})")
            "no_memory" -> AchievementSession.problem(c, "The emulator didn't give access to the game's memory, so nothing can unlock")
            "processing" -> AchievementSession.step(c, "Checking achievements every frame")
            "unlocked" -> AchievementSession.unlocked(c, title ?: "")
            "disconnected" -> AchievementSession.log(c, "Lost connection to RetroAchievements")
            "reconnected" -> AchievementSession.log(c, "Connection back")
        }
    }

    override fun onConnection(waiting: Boolean) {
        if (waiting && !warnedOffline) {
            warnedOffline = true
            show(
                Popup(
                    "RETROACHIEVEMENTS",
                    "Can't reach RetroAchievements",
                    "Unlocks are kept and sent when the connection is back. Keep the game open until then.",
                    null,
                    false,
                    6,
                ),
            )
        } else if (!waiting && warnedOffline) {
            warnedOffline = false
            show(Popup("RETROACHIEVEMENTS", "Back online", "Waiting unlocks were sent.", null, false, 3))
        }
    }

    @androidx.compose.runtime.Composable
    private fun Host() {
        LaunchedEffect(Unit) {
            while (true) {
                val next = queue.firstOrNull()
                if (next == null) {
                    delay(250)
                    continue
                }
                queue.removeAt(0)
                current = next
                visible = true
                delay(next.seconds * 1000L)
                visible = false
                delay(400)
            }
        }
        Box(Modifier.fillMaxSize().padding(top = 20.dp), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                current?.let { Card(it) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Card(popup: Popup) {
        val lime = Color(0xFF8FDC4E)
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF0161C26))
                .border(2.dp, if (popup.highlight) lime else Color(0xFF2A3546), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (popup.imageUrl != null) {
                AsyncImage(
                    model = popup.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(popup.label, color = lime, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    popup.title,
                    color = Color(0xFFE8EDF3),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                popup.detail?.let {
                    Text(it, color = Color(0xFFA9B4C2), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
