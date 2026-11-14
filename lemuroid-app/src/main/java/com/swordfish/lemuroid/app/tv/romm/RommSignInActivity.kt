package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommDeviceCode
import com.swordfish.lemuroid.lib.romm.RommSession
import com.swordfish.lemuroid.lib.romm.RommTokenPoll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * First-run sign in. The TV asks RomM for a short code, shows it with a QR code,
 * and waits while the owner approves it in RomM on a phone or computer. No password is typed on the TV.
 */
class RommSignInActivity : ComponentActivity() {
    private lateinit var session: RommSession
    private lateinit var client: RommClient

    private var server by mutableStateOf("")
    private var code by mutableStateOf<RommDeviceCode?>(null)
    private var qr by mutableStateOf<Bitmap?>(null)
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.swordfish.lemuroid.app.tv.romm.PhoneUi.apply(this)
        session = RommSession(this)
        client = RommClient(session)
        server = session.serverUrl.removePrefix("https://")

        setContent { Screen() }
        requestCode()
    }

    private fun requestCode() {
        pollJob?.cancel()
        code = null
        qr = null
        busy = true
        status = "Asking your RomM server for a code…"
        val address = RommSession.normalizeServer(server)
        pollJob =
            lifecycleScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            client.startDeviceLogin(
                                server = address,
                                clientIdentifier = session.clientIdentifier,
                                deviceName = "RomM TV on ${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                                appVersion = BuildConfig.VERSION_NAME,
                            )
                        }
                    }
                busy = false
                val device =
                    result.getOrElse {
                        Timber.e(it, "Device login init failed")
                        status = "Couldn't reach $address. Check the address and that the ${com.swordfish.lemuroid.lib.romm.Device.word} is online, then choose Get a new code."
                        return@launch
                    }
                code = device
                qr = makeQr(device.verificationUrlComplete)
                status = "Waiting for you to approve this ${com.swordfish.lemuroid.lib.romm.Device.word} in RomM…"
                poll(address, device)
            }
    }

    private suspend fun poll(
        address: String,
        device: RommDeviceCode,
    ) {
        var interval = device.intervalSeconds * 1000L
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        while (lifecycleScope.isActive && System.currentTimeMillis() < deadline) {
            delay(interval)
            val result = withContext(Dispatchers.IO) { runCatching { client.pollDeviceLogin(address, device.deviceCode) } }
            when (val poll = result.getOrNull()) {
                is RommTokenPoll.Approved -> {
                    session.signIn(address, poll.token, poll.deviceId)
                    status = "Signed in. Loading your library…"
                    LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
                    setResult(Activity.RESULT_OK)
                    delay(600)
                    finish()
                    return
                }
                RommTokenPoll.SlowDown -> interval += 2000
                RommTokenPoll.Denied -> {
                    status = "The request was declined in RomM. Choose Get a new code to try again."
                    code = null
                    return
                }
                RommTokenPoll.Expired -> break
                RommTokenPoll.Pending, null -> Unit
            }
        }
        code = null
        status = "The code expired. Choose Get a new code."
    }

    private fun makeQr(text: String): Bitmap {
        val size = 400
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    /** On a phone the approval page opens right here in the browser; the app keeps waiting and signs in when you come back. */
    private fun openApprovalPage() {
        val url = code?.verificationUrlComplete ?: return
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { status = "Couldn't open a browser. Open ${code?.verificationUrl} yourself and enter the code." }
    }

    @Composable
    private fun Screen() {
        val phone = !com.swordfish.lemuroid.lib.romm.Device.isTv(this)
        val buttonFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Ground)
                    .padding(horizontal = if (phone) 32.dp else 60.dp, vertical = if (phone) 12.dp else 48.dp),
            horizontalArrangement = Arrangement.spacedBy(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (phone) 12.dp else 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFFB4EE7E), Color(0xFF46AA28), Color(0xFF0E5C0E)))),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("RomM TV", color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Connect to your RomM server",
                    color = TextColor,
                    fontSize = if (phone) 26.sp else 36.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = if (phone) 30.sp else 40.sp,
                )
                if (phone) {
                    Step(1, "Tap Open approval page and sign in to RomM. (Or scan the QR code with another device.)")
                } else {
                    Step(1, "On your phone, scan the QR code, or open the address shown and sign in to RomM.")
                }
                Step(2, "Check that the code matches the one on this ${com.swordfish.lemuroid.lib.romm.Device.word}, then choose Approve.")
                Step(3, "This ${com.swordfish.lemuroid.lib.romm.Device.word} signs itself in. You only do this once.")

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { requestCode() }),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextColor,
                            unfocusedTextColor = TextColor,
                            focusedBorderColor = Lime,
                            unfocusedBorderColor = Line,
                            focusedLabelColor = Lime,
                            unfocusedLabelColor = Quiet,
                            cursorColor = Lime,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                FocusButton(
                    text = if (busy) "Please wait…" else "Get a new code",
                    onClick = { if (!busy) requestCode() },
                    modifier = Modifier.focusRequester(buttonFocus),
                )
                if (phone && code != null) FocusButton(text = "Open approval page", onClick = { openApprovalPage() })
                Text(status, color = Quiet, fontSize = 14.sp, lineHeight = 20.sp)
            }

            Column(
                modifier =
                    Modifier
                        .width(if (phone) 300.dp else 360.dp)
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("YOUR CODE", color = Quiet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Text(
                    code?.userCode?.let { formatCode(it) } ?: "– – – –",
                    color = TextColor,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                )
                Box(
                    Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR code that opens the approval page",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(184.dp),
                        )
                    }
                }
                Text(
                    code?.verificationUrl?.removePrefix("https://") ?: "",
                    color = Lime,
                    fontSize = 14.sp,
                )
            }
        }
    }

    private fun formatCode(code: String): String = if (code.length == 8 && !code.contains("-")) code.take(4) + "-" + code.drop(4) else code

    @Composable
    private fun Step(
        n: Int,
        text: String,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Green),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(11.dp))
            Text(text, color = TextColor, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }

    @Composable
    private fun FocusButton(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var focused by remember { mutableStateOf(false) }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
            shape = RoundedCornerShape(8.dp),
            modifier =
                modifier
                    .focusSound().onFocusChanged { focused = it.isFocused }
                    .border(2.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(2.dp),
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    companion object {
        val Ground = Color(0xFF0D1117)
        val Panel = Color(0xFF161C26)
        val Line = Color(0xFF2A3444)
        val TextColor = Color(0xFFEEF2F6)
        val Quiet = Color(0xFFA7B2C2)
        val Lime = Color(0xFF8FDC4E)
        val Green = Color(0xFF107C10)
    }
}
