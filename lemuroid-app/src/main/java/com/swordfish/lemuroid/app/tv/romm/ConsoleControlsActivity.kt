package com.swordfish.lemuroid.app.tv.romm

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.input.ConsoleControls
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import kotlinx.coroutines.delay

/**
 * Controller buttons per console. Pick a console on the left; on the right each of the console's buttons
 * shows which controller button presses it. Select one, then press the controller button you want for it.
 * Everything is driven by the D-pad, and the capture listens for any face, shoulder, trigger, stick or menu button.
 */
class ConsoleControlsActivity : ComponentActivity() {
    private var systems by mutableStateOf<List<SystemID>>(emptyList())
    private var selected by mutableStateOf<SystemID?>(null)
    private var layout by mutableStateOf<Map<Int, Int>>(emptyMap())
    private var custom by mutableStateOf(false)
    private var capturing by mutableStateOf<ConsoleControls.ConsoleButton?>(null)
    private var message by mutableStateOf("")
    private var swallowUp: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.swordfish.lemuroid.app.tv.romm.PhoneUi.apply(this)
        val ids = intent.getStringArrayListExtra(EXTRA_SYSTEMS).orEmpty()
        systems =
            ids.mapNotNull { id -> SystemID.values().firstOrNull { it.dbname == id } }
                .distinct()
                .ifEmpty { DEFAULT_SYSTEMS }
        select(systems.first())
        setContent { Screen() }
    }

    private fun select(system: SystemID) {
        if (selected == system) return
        selected = system
        layout = ConsoleControls.layout(this, system)
        custom = ConsoleControls.isCustom(this, system)
        message = ""
    }

    private fun name(system: SystemID): String =
        runCatching { getString(GameSystem.findById(system.dbname).titleResId) }.getOrDefault(system.dbname.uppercase())

    private fun padFor(retroKey: Int): Int? = layout.entries.firstOrNull { it.value == retroKey }?.key

    private fun captured(padKey: Int) {
        val system = selected ?: return
        val target = capturing ?: return
        layout = ConsoleControls.assign(this, system, padKey, target.retroKey)
        custom = ConsoleControls.isCustom(this, system)
        message = "${name(system)} ${target.label} is now on ${ConsoleControls.padName(padKey)}."
        capturing = null
    }

    private fun reset() {
        val system = selected ?: return
        ConsoleControls.reset(this, system)
        layout = ConsoleControls.layout(this, system)
        custom = false
        message = "${name(system)} is back to the normal layout."
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (capturing != null) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && ConsoleControls.canCapture(key) -> {
                    swallowUp = key
                    captured(key)
                }
                event.action == KeyEvent.ACTION_UP && key == KeyEvent.KEYCODE_BACK -> {
                    capturing = null
                    message = "Nothing changed."
                }
            }
            return true
        }
        UiSounds.onKey(this, event)
        if (swallowUp == key) {
            if (event.action == KeyEvent.ACTION_UP) swallowUp = null
            return true
        }
        return when (key) {
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
        Box(Modifier.fillMaxSize().background(Ground)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
                Text("Controller buttons", color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Each console can have its own layout. Pick a console, select one of its buttons, then press the controller button you want for it. " +
                        "Changes apply the next time a game starts.",
                    color = Quiet,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                )
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ConsoleList(Modifier.width(260.dp).fillMaxHeight())
                    selected?.let { ButtonList(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
            capturing?.let { CaptureOverlay(it) }
        }
    }

    @Composable
    private fun ConsoleList(modifier: Modifier) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(systems) { system ->
                val isCustom = ConsoleControls.isCustom(this@ConsoleControlsActivity, system) || (system == selected && custom)
                Item(
                    modifier = if (system == systems.first()) Modifier.focusRequester(first) else Modifier,
                    selected = system == selected,
                    onFocus = { select(system) },
                    onClick = { select(system) },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(name(system), color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (isCustom) "Custom layout" else "Normal layout", color = if (isCustom) Lime else Quiet, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun ButtonList(
        system: SystemID,
        modifier: Modifier,
    ) {
        val buttons = ConsoleControls.consoleButtons(system)
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${name(system).uppercase()} BUTTON", color = Quiet, fontSize = 11.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                    Text("YOUR CONTROLLER", color = Quiet, fontSize = 11.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1.4f))
                }
            }
            items(buttons) { button ->
                Item(onClick = {
                    message = ""
                    capturing = button
                }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(button.label, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            padFor(button.retroKey)?.let { ConsoleControls.padName(it) } ?: "Not set",
                            color = Lime,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Item(onClick = { reset() }) {
                        Text(
                            "Reset to normal layout",
                            color = TextColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    if (message.isNotBlank()) Text(message, color = Quiet, fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun CaptureOverlay(button: ConsoleControls.ConsoleButton) {
        val system = selected ?: return
        var secondsLeft by remember(button) { mutableStateOf(8) }
        LaunchedEffect(button) {
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft--
            }
            if (capturing == button) {
                capturing = null
                message = "No button pressed, nothing changed."
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xCC05080C)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(2.dp, Lime, RoundedCornerShape(12.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("PRESS A BUTTON", color = Lime, fontSize = 12.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text("${name(system)} ${button.label}", color = TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Press the controller button you want for it. Its current button moves to the one you press.",
                    color = Quiet,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text("Back cancels · closes in $secondsLeft s", color = Quiet, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun Item(
        modifier: Modifier = Modifier,
        selected: Boolean = false,
        onFocus: () -> Unit = {},
        onClick: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier
                .fillMaxWidth()
                .focusSound().onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .border(2.dp, if (focused) Lime else if (selected) Line else Color.Transparent, RoundedCornerShape(9.dp))
                .padding(2.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) Color(0xFF1E2633) else Panel)
                .clickable(onClick = onClick),
        ) {
            content()
        }
    }

    companion object {
        private const val EXTRA_SYSTEMS = "systems"

        private val Ground = RommSignInActivity.Ground
        private val Panel = RommSignInActivity.Panel
        private val Line = RommSignInActivity.Line
        private val TextColor = RommSignInActivity.TextColor
        private val Quiet = RommSignInActivity.Quiet
        private val Lime = RommSignInActivity.Lime

        private val DEFAULT_SYSTEMS =
            listOf(
                SystemID.NES, SystemID.SNES, SystemID.N64, SystemID.GB, SystemID.GBC, SystemID.GBA, SystemID.NDS,
                SystemID.GENESIS, SystemID.SMS, SystemID.GG, SystemID.PSX, SystemID.PSP, SystemID.FBNEO,
            )

        fun open(
            context: Context,
            systems: List<SystemID>,
        ) {
            context.startActivity(
                Intent(context, ConsoleControlsActivity::class.java)
                    .putStringArrayListExtra(EXTRA_SYSTEMS, ArrayList(systems.map { it.dbname })),
            )
        }
    }
}
