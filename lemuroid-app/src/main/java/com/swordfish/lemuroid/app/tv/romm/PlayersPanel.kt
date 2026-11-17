package com.swordfish.lemuroid.app.tv.romm

import android.view.InputDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One connected controller and the player it plays as (1 = player 1). */
data class Player(val number: Int, val name: String)

/**
 * The controllers games will use, in player order: the same list and order the game itself uses to
 * hand out player 1, 2, 3, 4.
 */
fun playersFlow(manager: InputDeviceManager): Flow<List<Player>> =
    manager.getEnabledInputsObservable()
        .map { devices -> devices.mapIndexed { i, d -> Player(i + 1, friendlyName(d)) } }
        .distinctUntilChanged()

/** Controller names as people know them ("Xbox Wireless Controller", not "Microsoft X-Box 360 pad 0"). */
fun friendlyName(device: InputDevice): String {
    val raw = device.name?.trim().orEmpty()
    val lower = raw.lowercase()
    return when {
        raw.isBlank() -> "Controller"
        "dualsense" in lower -> "PlayStation 5 controller"
        "wireless controller" == lower || "dualshock" in lower -> "PlayStation controller"
        "x-box" in lower || "xbox" in lower -> if ("xbox wireless" in lower) raw else "Xbox controller"
        "pro controller" in lower -> "Switch Pro Controller"
        "joy-con" in lower -> raw
        "8bitdo" in lower -> raw.replace(Regex("(?i)8bitdo"), "8BitDo")
        else -> raw.replace(Regex("\\s+\\d+$"), "")
    }
}

private val PlayerColors =
    listOf(Color(0xFF4FA3FF), Color(0xFFFF5D5D), Color(0xFF8FDC4E), Color(0xFFFFC542))

fun playerColor(number: Int) = PlayerColors[(number - 1).mod(PlayerColors.size)]

/**
 * A small strip of connected controllers: a coloured P1/P2/P3/P4 badge with the controller's name under it.
 * Shows even with one controller, so you can see who's player 1 before starting.
 */
@Composable
fun PlayersPanel(
    manager: InputDeviceManager,
    modifier: Modifier = Modifier,
) {
    val flow = remember(manager) { playersFlow(manager) }
    val players by flow.collectAsState(initial = emptyList())
    if (players.isEmpty()) return
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE0161C26))
            .border(1.dp, RommSignInActivity.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        players.take(4).forEach { PlayerBadge(it) }
    }
}

@Composable
fun PlayerBadge(player: Player) {
    val color = playerColor(player.number)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 64.dp, max = 130.dp)) {
        if (player.number <= 4) {
            PlayerLabelIcon(player.number, color)
        } else {
            Text("P${player.number}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        PlayerPadIcon(player.number, color, modifier = Modifier.padding(top = 2.dp))
        Text(
            player.name,
            color = RommSignInActivity.TextColor,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
