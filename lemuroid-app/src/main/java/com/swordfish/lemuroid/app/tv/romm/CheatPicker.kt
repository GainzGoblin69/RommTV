package com.swordfish.lemuroid.app.tv.romm

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Panel = RommSignInActivity.Panel
private val Line = RommSignInActivity.Line
private val TextColor = RommSignInActivity.TextColor
private val Quiet = RommSignInActivity.Quiet
private val Lime = RommSignInActivity.Lime
private val Raised = Color(0xFF1E2633)

/**
 * The cheat list for one game: ready-made cheats from the libretro library plus your own codes.
 * Switching one on takes effect the next time the game starts. [onChanged] gets how many are on.
 */
@Composable
fun CheatPicker(
    game: Game,
    onChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var library by remember(game.id) { mutableStateOf<List<GameCheats.Cheat>?>(null) }
    var custom by remember(game.id) { mutableStateOf(GameCheats.customCheats(context, game)) }
    var onSet by remember(game.id) { mutableStateOf(GameCheats.enabled(context, game).map { it.description to it.code }.toSet()) }
    var adding by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    LaunchedEffect(game.id) {
        library = withContext(Dispatchers.IO) { GameCheats.library(context.applicationContext, game) }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun refresh() {
        custom = GameCheats.customCheats(context, game)
        val enabled = GameCheats.enabled(context, game)
        onSet = enabled.map { it.description to it.code }.toSet()
        onChanged(enabled.size)
    }

    fun isOn(c: GameCheats.Cheat) = (c.description to c.code) in onSet

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .width(600.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Cheats", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Cheats switch on when the game starts. Player 2 cheats help a second player. " +
                    "RetroAchievements pause while any cheat is on.",
                color = Quiet,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            if (adding) {
                AddCode(
                    onAdd = { desc, code ->
                        GameCheats.addCustom(context, game, desc, code)
                        adding = false
                        refresh()
                    },
                    onCancel = { adding = false },
                )
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Done", Modifier.focusRequester(first)) { onDismiss() }
                if (onSet.isNotEmpty()) {
                    SmallButton("All off (${onSet.size})") {
                        GameCheats.allOff(context, game)
                        refresh()
                    }
                }
                SmallButton("+ Add your own code") { adding = true }
            }
            val list = library
            val shown =
                list.orEmpty().filter {
                    filter.isBlank() || it.description.contains(filter, ignoreCase = true)
                }
            if ((list?.size ?: 0) > 12) {
                SearchField(filter) { filter = it }
            }
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (custom.isNotEmpty()) {
                    Heading("YOUR CODES")
                    custom.forEach { c ->
                        CheatRow(c, isOn(c), onRemove = {
                            GameCheats.removeCustom(context, game, c)
                            refresh()
                        }) {
                            GameCheats.setOn(context, game, c, !isOn(c))
                            refresh()
                        }
                    }
                }
                when {
                    list == null -> Text("Looking up cheats for this game…", color = Quiet, fontSize = 14.sp)
                    list.isEmpty() ->
                        Text(
                            "No ready-made cheats were found for this game. You can still add a Game Genie, " +
                                "Action Replay or GameShark code with \"Add your own code\".",
                            color = Quiet,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                        )
                    else -> {
                        Heading("FROM THE CHEAT LIBRARY (${list.size})")
                        // People reach for these the most, so they go first
                        val sorted = shown.sortedByDescending { score(it.description) }
                        sorted.forEach { c ->
                            CheatRow(c, isOn(c)) {
                                GameCheats.setOn(context, game, c, !isOn(c))
                                refresh()
                            }
                        }
                        if (shown.isEmpty()) Text("Nothing matches \"$filter\".", color = Quiet, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Handy ones (health, lives, invincibility) rise to the top of the list. */
private fun score(description: String): Int {
    val d = description.lowercase()
    return listOf("infinite health", "invincib", "invicib", "infinite energy", "infinite lives", "health", "lives", "energy", "never die")
        .withIndex().firstOrNull { d.contains(it.value) }?.let { 20 - it.index } ?: 0
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun CheatRow(
    cheat: GameCheats.Cheat,
    on: Boolean,
    onRemove: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .focusSound()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Lime else Line, shape)
                .clip(shape)
                .background(if (on) Color(0xFF1D3317) else Raised)
                .tapClickable(onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Lime else Color.Transparent)
                    .border(2.dp, if (on) Lime else Quiet, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Text("✓", color = Color(0xFF0D1117), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cheat.description, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (cheat.custom) Text(cheat.code, color = Quiet, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (on) "ON" else "OFF", color = if (on) Lime else Quiet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (onRemove != null) SmallButton("Delete") { onRemove() }
    }
}

@Composable
private fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusSound()
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(11.dp))
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Raised)
            .tapClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
) {
    InputField("Search cheats (e.g. health, lives)", value, onChange, ImeAction.Search) {}
}

@Composable
private fun AddCode(
    onAdd: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InputField("What it does (e.g. Player 2 infinite health)", description, { description = it }, ImeAction.Next, Modifier.focusRequester(first)) {}
        InputField("Code (Game Genie, Action Replay…; join several with +)", code, { code = it.uppercase() }, ImeAction.Done) {
            if (code.isNotBlank()) onAdd(description, code)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Add and switch on") { if (code.isNotBlank()) onAdd(description, code) }
            SmallButton("Cancel") { onCancel() }
        }
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(label.uppercase(), color = Quiet, fontSize = 11.sp, letterSpacing = 1.2.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TextColor, fontSize = 16.sp),
            cursorBrush = SolidColor(Lime),
            keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrect = false, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions =
                KeyboardActions(
                    onNext = { focus.moveFocus(FocusDirection.Down) },
                    onDone = { onDone() },
                    onSearch = { focus.moveFocus(FocusDirection.Down) },
                ),
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusSound()
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val native = e.nativeKeyEvent
                        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> focus.moveFocus(FocusDirection.Down)
                            KeyEvent.KEYCODE_DPAD_UP -> focus.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .border(2.dp, if (focused) Lime else Line, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Raised)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { inner() }
            },
        )
    }
}
