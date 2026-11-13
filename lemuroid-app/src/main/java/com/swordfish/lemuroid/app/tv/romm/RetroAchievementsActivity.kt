package com.swordfish.lemuroid.app.tv.romm

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
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → RetroAchievements: sign in once on this device, then achievements unlock while you play. */
class RetroAchievementsActivity : ComponentActivity() {
    private var account by mutableStateOf<RetroAchievementsAccount.Account?>(null)
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneUi.apply(this)
        account = RetroAchievementsAccount.load(this)
        if (account == null) {
            // Start with the RetroAchievements username from your RomM profile, if it has one
            lifecycleScope.launch {
                val fromRomm =
                    withContext(Dispatchers.IO) { runCatching { RommClient(RommSession(applicationContext)).raUsername() }.getOrNull() }
                if (username.isBlank() && fromRomm != null) username = fromRomm
            }
        }
        setContent { Screen() }
    }

    private fun signIn() {
        if (busy) return
        if (username.isBlank() || password.isEmpty()) {
            message = "Enter your RetroAchievements username and password."
            return
        }
        busy = true
        message = "Signing in…"
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { RetroAchievementsAccount.signIn(applicationContext, username, password) }
                }
            busy = false
            password = ""
            result.onSuccess {
                account = it
                message = "Signed in. Achievements unlock while you play."
            }.onFailure {
                message = it.message ?: "Sign in didn't work."
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A ->
                super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount))
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) finish()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(RommSignInActivity.Ground)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("RetroAchievements", color = RommSignInActivity.TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Earn RetroAchievements while you play. A pop-up shows each unlock, and the game page updates " +
                        "after you play (if your RomM profile has your RetroAchievements username).",
                    color = RommSignInActivity.Quiet,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.width(720.dp),
                )
                val current = account
                if (current != null) SignedIn(current) else SignInForm()
                if (message.isNotBlank()) Text(message, color = RommSignInActivity.Lime, fontSize = 14.sp)
                Text(
                    "Achievements are earned in softcore mode. RetroAchievements only counts hardcore unlocks from emulators " +
                        "it has reviewed, and RomM TV isn't one of them. Your password isn't kept on this device, only a sign-in key " +
                        "from RetroAchievements.",
                    color = RommSignInActivity.Quiet,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.width(720.dp).padding(top = 10.dp),
                )
            }
        }
    }

    @Composable
    private fun SignedIn(current: RetroAchievementsAccount.Account) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Text("Signed in as ${current.username}", color = RommSignInActivity.TextColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                if (current.enabled) "Achievements: On" else "Achievements: Off",
                Modifier.focusRequester(first),
            ) {
                RetroAchievementsAccount.setEnabled(this@RetroAchievementsActivity, !current.enabled)
                account = RetroAchievementsAccount.load(this@RetroAchievementsActivity)
                message = if (account?.enabled == true) "Achievements are on." else "Achievements are off. Games run without them."
            }
            Tile("Sign out") {
                RetroAchievementsAccount.signOut(this@RetroAchievementsActivity)
                account = null
                message = "Signed out."
            }
        }
    }

    @Composable
    private fun SignInForm() {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Field("Username", username, { username = it }, Modifier.focusRequester(first), password = false)
        Field("Password", password, { password = it }, Modifier, password = true)
        Tile(if (busy) "Signing in…" else "Sign in") { signIn() }
        Text(
            "No account? Make one for free at retroachievements.org.",
            color = RommSignInActivity.Quiet,
            fontSize = 13.sp,
        )
    }

    @Composable
    private fun Field(
        label: String,
        value: String,
        onChange: (String) -> Unit,
        modifier: Modifier,
        password: Boolean,
    ) {
        val focus = LocalFocusManager.current
        var focused by remember { mutableStateOf(false) }
        Column {
            Text(label.uppercase(), color = RommSignInActivity.Quiet, fontSize = 11.sp, letterSpacing = 1.4.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = RommSignInActivity.TextColor, fontSize = 18.sp),
                cursorBrush = SolidColor(RommSignInActivity.Lime),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                        imeAction = if (password) ImeAction.Done else ImeAction.Next,
                        autoCorrect = false,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onNext = { focus.moveFocus(FocusDirection.Down) },
                        onDone = { signIn() },
                    ),
                modifier =
                    modifier
                        .width(420.dp)
                        .padding(top = 4.dp)
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
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier
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
}
