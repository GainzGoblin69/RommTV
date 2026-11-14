package com.swordfish.lemuroid.app.tv.romm

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Menu music: where the music folder is, how loud, and when the screenshot slideshow starts. */
class MenuMusicActivity : ComponentActivity() {
    private var address by mutableStateOf("")
    private var password by mutableStateOf("")
    private var enabled by mutableStateOf(true)
    private var volume by mutableStateOf(1)
    private var idle by mutableStateOf(2)
    private var message by mutableStateOf("")
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneUi.apply(this)
        val s = MenuMusic.settings(this)
        address = s.url.ifBlank { SUGGESTED }
        password = s.password
        enabled = s.enabled
        volume = s.volume
        idle = s.idleMinutes
        setContent { Screen() }
    }

    private fun current() = MenuMusic.Settings(address, password, enabled, volume, idle)

    private fun store() = MenuMusic.save(this, current())

    private fun testAndSave() {
        if (busy) return
        busy = true
        message = "Looking in the music folder…"
        val settings = current().copy(url = MenuMusic.normalize(address))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MenuMusic.scan(settings) } }
            busy = false
            result.onSuccess { tracks ->
                address = settings.url
                store()
                val albums = tracks.map { it.album }.distinct().size
                message =
                    if (tracks.isEmpty()) {
                        "Connected, but there are no songs in that folder yet (MP3, FLAC, OGG, M4A, Opus or WAV)."
                    } else {
                        "Found ${tracks.size} songs in $albums album${if (albums == 1) "" else "s"}. Saved: music starts shortly."
                    }
            }.onFailure {
                message = "Couldn't read the music folder: ${it.message ?: "no answer"}. Check the address and password."
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Idle.consumeKey(event)) return true
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Idle.touch()
        return super.dispatchTouchEvent(ev)
    }

    @Composable
    private fun Screen() {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Box(Modifier.fillMaxSize().background(RommSignInActivity.Ground)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Menu music", color = RommSignInActivity.TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Shuffles every song in your music folder while you browse. Drop album folders into it on your server and " +
                        "they're picked up next time the app starts. Music fades out when a game starts and back in when you return.",
                    color = RommSignInActivity.Quiet,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.width(760.dp),
                )
                Field("Music folder address", address, { address = it }, Modifier.focusRequester(first), secret = false)
                Field("Password", password, { password = it }, Modifier, secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(if (busy) "Checking…" else "Test and save") { testAndSave() }
                    Tile("Music: " + if (enabled) "On" else "Off") {
                        enabled = !enabled
                        store()
                    }
                    Tile("Volume: " + MenuMusic.VOLUME_NAMES[volume.coerceIn(0, MenuMusic.VOLUME_NAMES.lastIndex)]) {
                        volume = (volume + 1) % MenuMusic.VOLUMES.size
                        store()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tile("Screenshot slideshow: " + if (idle <= 0) "Off" else "after $idle min") {
                        idle = IDLE_CHOICES[(IDLE_CHOICES.indexOf(idle) + 1).mod(IDLE_CHOICES.size)]
                        store()
                    }
                    Tile("Skip song") { MenuMusic.skip() }
                    NowPlayingChip()
                }
                if (message.isNotBlank()) Text(message, color = RommSignInActivity.Lime, fontSize = 14.sp, modifier = Modifier.width(760.dp))
                Text(
                    "The slideshow starts when nothing has been pressed for a while: random screenshots from your games, full screen, " +
                        "with the song that's playing. Any button brings you back to where you were.",
                    color = RommSignInActivity.Quiet,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.width(760.dp).padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun Field(
        label: String,
        value: String,
        onChange: (String) -> Unit,
        modifier: Modifier,
        secret: Boolean,
    ) {
        val focus = LocalFocusManager.current
        var focused by remember { mutableStateOf(false) }
        Column {
            Text(label.uppercase(), color = RommSignInActivity.Quiet, fontSize = 11.sp, letterSpacing = 1.4.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = RommSignInActivity.TextColor, fontSize = 17.sp),
                cursorBrush = SolidColor(RommSignInActivity.Lime),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                        imeAction = if (secret) ImeAction.Done else ImeAction.Next,
                        autoCorrect = false,
                    ),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { testAndSave() }),
                modifier =
                    modifier
                        .width(620.dp)
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
                            .border(2.dp, if (focused) RommSignInActivity.Lime else RommSignInActivity.Line, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(RommSignInActivity.Panel)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) { inner() }
                },
            )
        }
    }

    @Composable
    private fun Tile(
        text: String,
        onClick: () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .focusSound()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) RommSignInActivity.Lime else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) RommSignInActivity.Green else RommSignInActivity.Panel)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = RommSignInActivity.TextColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    companion object {
        /** Where the setup steps put it (Copyparty on Whatbox, behind the "music" app link). */
        const val SUGGESTED = "https://music.alice99.box.ca/music/"
        private val IDLE_CHOICES = listOf(1, 2, 5, 10, 0)
    }
}
