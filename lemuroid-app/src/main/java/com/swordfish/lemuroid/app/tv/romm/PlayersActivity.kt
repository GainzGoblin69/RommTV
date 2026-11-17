package com.swordfish.lemuroid.app.tv.romm

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

/**
 * Players: every connected controller in player order. Move one up or down to change who's player 1, 2, 3, 4,
 * or switch one off so games ignore it (it then takes no player number). Changes apply straight away, also to
 * a game that's running. Opened from Settings, and from the game menu (as [GamePlayersActivity]).
 */
open class PlayersActivity : ComponentActivity() {
    private lateinit var manager: InputDeviceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneUi.apply(this)
        val prefs = SharedPreferencesHelper.getSharedPreferences(applicationContext)
        manager = InputDeviceManager(applicationContext, dagger.Lazy<android.content.SharedPreferences> { prefs })
        setContent { Screen() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        UiSounds.onKey(this, event)
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

    @Composable
    private fun Screen() {
        val flow = remember { manager.getConnectedPadsObservable() }
        val pads by flow.collectAsState(initial = emptyList())
        val first = remember { FocusRequester() }
        LaunchedEffect(pads.isEmpty()) { runCatching { first.requestFocus() } }

        fun move(
            index: Int,
            by: Int,
        ) {
            val target = index + by
            if (target !in pads.indices) return
            val order = pads.map { it.device }.toMutableList()
            order.add(target, order.removeAt(index))
            manager.setOrder(order)
        }

        Column(
            Modifier.fillMaxSize().background(Ground).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Players", color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "The controller at the top is player 1. Move a controller up or down to change who's who, or switch " +
                    "one off so games ignore it. Changes apply straight away.",
                color = Quiet,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
            if (pads.isEmpty()) {
                Text("No controllers are connected.", color = TextColor, fontSize = 16.sp)
            }
            var number = 0
            pads.forEachIndexed { index, pad ->
                val player = if (pad.enabled) ++number else 0
                // Keyed by controller, so the selection travels with a controller as it moves up or down
                androidx.compose.runtime.key(pad.device.descriptor + "#" + pad.device.id) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        Modifier.width(64.dp).alpha(if (pad.enabled) 1f else 0.35f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (player in 1..4) PlayerLabelIcon(player, playerColor(player))
                        PlayerPadIcon(if (pad.enabled) player else 2, if (pad.enabled) playerColor(player) else Quiet)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            friendlyName(pad.device),
                            color = if (pad.enabled) TextColor else Quiet,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (pad.enabled) "Player $player" else "Off: games ignore this controller",
                            color = if (pad.enabled) playerColor(player) else Quiet,
                            fontSize = 13.sp,
                        )
                    }
                    Button("▲ Up", enabled = index > 0) { move(index, -1) }
                    Button("▼ Down", enabled = index < pads.lastIndex) { move(index, 1) }
                    Button(
                        if (pad.enabled) "On" else "Off",
                        highlight = pad.enabled,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    ) { manager.setEnabled(pad.device, !pad.enabled) }
                }
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            Button("Done", modifier = if (pads.isEmpty()) Modifier.focusRequester(first) else Modifier) { finish() }
        }
    }

    @Composable
    private fun Button(
        text: String,
        enabled: Boolean = true,
        highlight: Boolean = false,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier
                .widthIn(min = 76.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .focusSound()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(11.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (highlight) Green else Raised)
                .tapClickable { if (enabled) onClick() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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

/** The same screen opened from the game menu; it runs alongside the game so the menu music stays off. */
class GamePlayersActivity : PlayersActivity()
