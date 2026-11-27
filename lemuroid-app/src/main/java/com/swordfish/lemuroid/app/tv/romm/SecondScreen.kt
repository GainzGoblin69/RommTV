package com.swordfish.lemuroid.app.tv.romm

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.RetroAchievements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Dual-screen handhelds (like the AYN Thor): the second display can show a companion while you play
 * (this game's achievements, the guide, or music controls), and DS / 3DS games can put their bottom
 * screen there, touch included.
 */
object SecondScreen {
    private const val PREFS = "romm_ui"
    private const val COMPANION = "second_screen_companion"
    private const val DUAL = "second_screen_ds"
    private const val TAB = "second_screen_tab"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The other screen, if this device has one (a built-in second screen, or one plugged in). */
    fun display(context: Context): Display? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val presentation = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return presentation.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: manager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    fun available(context: Context) = runCatching { display(context) != null }.getOrDefault(false)

    fun companionEnabled(context: Context) = prefs(context).getBoolean(COMPANION, true)

    fun setCompanionEnabled(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit().putBoolean(COMPANION, on).apply()

    fun dualEnabled(context: Context) = prefs(context).getBoolean(DUAL, true)

    fun setDualEnabled(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit().putBoolean(DUAL, on).apply()

    fun tab(context: Context) = prefs(context).getString(TAB, "achievements") ?: "achievements"

    fun setTab(
        context: Context,
        tab: String,
    ) = prefs(context).edit().putString(TAB, tab).apply()

    /**
     * How a two-screen console is split: the emulator draws both screens stacked (no gap), the main screen
     * shows `main` and the second display shows `second` (fractions of the picture, top-left origin).
     */
    class Split(val main: RectF, val second: RectF, val aspect: Float, val options: Map<String, String>)

    private val TOP = RectF(0f, 0f, 1f, 0.5f)
    private val BOTTOM = RectF(0f, 0.5f, 1f, 1f)

    fun split(core: CoreID): Split? =
        when (core) {
            CoreID.DESMUME ->
                Split(TOP, BOTTOM, 256f / 384f, mapOf("desmume_screens_layout" to "top/bottom", "desmume_screens_gap" to "0"))
            CoreID.MELONDS ->
                Split(
                    TOP,
                    BOTTOM,
                    256f / 384f,
                    mapOf(
                        "melonds_number_of_screen_layouts" to "1",
                        "melonds_screen_layout1" to "top-bottom",
                        "melonds_screen_gap" to "0",
                    ),
                )
            // 3DS: the 400-wide top screen over the 320-wide bottom one, centred
            CoreID.CITRA ->
                Split(TOP, RectF(0.1f, 0.5f, 0.9f, 1f), 400f / 480f, mapOf("citra_layout_option" to "Default Top-Bottom Screen"))
            else -> null
        }

    /** The split to use for this game right now, or null (no second screen, switched off, or not a DS / 3DS). */
    fun activeSplit(
        context: Context,
        system: SystemID,
        config: SystemCoreConfig,
    ): Split? {
        if (system != SystemID.NDS && system != SystemID.NINTENDO_3DS) return null
        if (!dualEnabled(context) || !available(context)) return null
        return split(config.coreID)
    }
}

/** What shows on the second display while a game runs. */
@SuppressLint("ClickableViewAccessibility")
class SecondScreenPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val game: Game,
    private val retroView: GLRetroView,
    private val split: SecondScreen.Split?,
) : Presentation(activity, display) {
    private var guide: GuidePanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Controllers keep talking to the game; the second screen only takes touches
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        if (split != null) setContentView(bottomScreen(split)) else setContentView(companion())
    }

    private fun bottomScreen(split: SecondScreen.Split): SurfaceView {
        retroView.aspectRatioForSecondary = split.aspect
        val surface = SurfaceView(context)
        surface.holder.addCallback(
            object : SurfaceHolder.Callback {
                private var attached = false

                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    if (attached) {
                        retroView.resizeSecondary(width, height)
                    } else {
                        retroView.attachSecondary(holder.surface, width, height, split.second)
                        attached = true
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    attached = false
                    retroView.detachSecondary()
                }
            },
        )
        surface.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> retroView.secondaryTouch(event.x, event.y, false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> retroView.secondaryTouch(event.x, event.y, true)
            }
            true
        }
        surface.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return surface
    }

    private fun companion(): ComposeView =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent { Companion() }
        }

    override fun onStop() {
        guide?.rememberPlace()
        retroView.detachSecondary()
        super.onStop()
    }

    private fun guidePanel(): GuidePanel =
        guide ?: GuidePanel(activity, game.title, game.fileUri, game.systemId, context).also { guide = it }

    // ---- the companion

    @Composable
    private fun Companion() {
        var tab by remember { mutableStateOf(SecondScreen.tab(activity)) }
        Column(Modifier.fillMaxSize().background(RommSignInActivity.Ground).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "achievements" to "Achievements",
                    "guide" to "Guide",
                    "music" to "Sound",
                ).forEach { (key, label) ->
                    TabButton(label, tab == key, Modifier.weight(1f)) {
                        if (tab == "guide" && key != "guide") guide?.rememberPlace()
                        tab = key
                        SecondScreen.setTab(activity, key)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    "guide" -> GuideTab()
                    "music" -> SoundTab()
                    else -> AchievementsTab()
                }
            }
        }
    }

    @Composable
    private fun TabButton(
        label: String,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        Box(
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) RommSignInActivity.Lime else RommSignInActivity.Panel)
                .border(1.dp, if (selected) RommSignInActivity.Lime else RommSignInActivity.Line, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) RommSignInActivity.Ground else RommSignInActivity.TextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun ActionButton(
        label: String,
        modifier: Modifier = Modifier,
        accent: Boolean = false,
        onClick: () -> Unit,
    ) {
        Box(
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (accent) RommSignInActivity.Green else RommSignInActivity.Panel)
                .border(1.dp, RommSignInActivity.Line, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = RommSignInActivity.TextColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }

    // ---- achievements

    private class Cheevo(
        val title: String,
        val description: String,
        val badge: String,
        val progress: String,
        val percent: Float,
        val points: Int,
        val unlocked: Boolean,
    )

    private class Bucket(val label: String, val items: List<Cheevo>)

    private class CheevoList(
        val game: String,
        val total: Int,
        val unlocked: Int,
        val points: Int,
        val pointsUnlocked: Int,
        val buckets: List<Bucket>,
    )

    private fun parse(json: String): CheevoList? {
        if (json.isBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            val buckets = o.optJSONArray("buckets")
            CheevoList(
                game = o.optString("game"),
                total = o.optInt("total"),
                unlocked = o.optInt("unlocked"),
                points = o.optInt("points"),
                pointsUnlocked = o.optInt("pointsUnlocked"),
                buckets =
                    (0 until (buckets?.length() ?: 0)).map { b ->
                        val bucket = buckets!!.getJSONObject(b)
                        val items = bucket.optJSONArray("items")
                        Bucket(
                            bucket.optString("label"),
                            (0 until (items?.length() ?: 0)).map { i ->
                                val a = items!!.getJSONObject(i)
                                Cheevo(
                                    a.optString("title"),
                                    a.optString("description"),
                                    a.optString("badge"),
                                    a.optString("progress"),
                                    a.optDouble("percent", 0.0).toFloat(),
                                    a.optInt("points"),
                                    a.optBoolean("unlocked"),
                                )
                            },
                        )
                    }.filter { it.items.isNotEmpty() },
            )
        }.getOrNull()
    }

    @Composable
    private fun AchievementsTab() {
        var list by remember { mutableStateOf<CheevoList?>(null) }
        var checked by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                list = withContext(Dispatchers.Default) { parse(RetroAchievements.achievementList()) }
                checked = true
                delay(3000)
            }
        }
        val data = list
        if (data == null || data.total == 0) {
            val signedIn = RetroAchievementsAccount.load(activity)?.enabled == true
            Message(
                when {
                    !checked -> "Loading achievements…"
                    !signedIn -> "Sign in to RetroAchievements in Settings to see this game's achievements here."
                    data != null -> "This game has no achievements yet."
                    else -> "No achievements for this game. (They pause while cheats are on, and need a connection the first time.)"
                },
            )
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(RommSignInActivity.Panel)
                        .padding(12.dp),
                ) {
                    Text(
                        data.game.ifBlank { game.title },
                        color = RommSignInActivity.TextColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${data.unlocked} of ${data.total} unlocked · ${data.pointsUnlocked} of ${data.points} points",
                        color = RommSignInActivity.Quiet,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    Bar(if (data.total > 0) data.unlocked.toFloat() / data.total else 0f, Modifier.fillMaxWidth().height(6.dp))
                }
            }
            data.buckets.forEach { bucket ->
                item {
                    Text(
                        bucket.label.uppercase(),
                        color = RommSignInActivity.Lime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(bucket.items) { CheevoRow(it) }
            }
        }
    }

    @Composable
    private fun CheevoRow(a: Cheevo) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(RommSignInActivity.Panel)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            coil.compose.AsyncImage(
                model = "${RaWeb.MEDIA}/Badge/${a.badge}${if (a.unlocked) "" else "_lock"}.png",
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    a.title,
                    color = if (a.unlocked) RommSignInActivity.TextColor else RommSignInActivity.TextColor.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(a.description, color = RommSignInActivity.Quiet, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!a.unlocked && a.progress.isNotBlank()) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Bar(a.percent / 100f, Modifier.weight(1f).height(4.dp))
                        Text(a.progress, color = RommSignInActivity.Quiet, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            Text(
                "${a.points}",
                color = if (a.unlocked) RommSignInActivity.Lime else RommSignInActivity.Quiet,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.End,
            )
        }
    }

    // ---- guide

    @Composable
    private fun GuideTab() {
        val panel = remember { guidePanel() }
        var status by remember { mutableStateOf(panel.status) }
        DisposableEffect(panel) {
            panel.onStatus = { status = it }
            panel.show()
            onDispose {
                panel.onStatus = {}
                panel.rememberPlace()
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Where am I?", Modifier.weight(1f), accent = true) { panel.jumpToSuggestion() }
                ActionButton("Top", Modifier.weight(0.6f)) { panel.toTop() }
                ActionButton("Other guides", Modifier.weight(1f)) { panel.otherGuides() }
            }
            if (status.isNotBlank()) {
                Text(
                    status.replace(" · Y jumps to where you are", ""),
                    color = RommSignInActivity.Quiet,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
            AndroidView(
                factory = {
                    (panel.frame.parent as? ViewGroup)?.removeView(panel.frame)
                    FrameLayout(it).apply {
                        addView(panel.frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)),
            )
        }
    }

    // ---- sound

    @Composable
    private fun SoundTab() {
        var gameSound by remember { mutableStateOf(retroView.audioEnabled) }
        var johnnyOn by remember { mutableStateOf(JohnnyMusic.isOn) }
        var song by remember { mutableStateOf(JohnnyMusic.nowPlaying) }
        val johnny = remember { JohnnyMusic.isUnlocked(activity) }
        LaunchedEffect(Unit) {
            while (true) {
                gameSound = retroView.audioEnabled
                johnnyOn = JohnnyMusic.isOn
                song = JohnnyMusic.nowPlaying
                delay(1000)
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow("Game sound", if (gameSound) "On" else "Off", gameSound) {
                gameSound = !gameSound
                retroView.audioEnabled = gameSound
            }
            if (johnny) {
                ToggleRow("Johnny Music", if (johnnyOn) "Shuffling, game muted" else "Off", johnnyOn) {
                    val turnOn = !JohnnyMusic.isOn
                    JohnnyMusic.setOn(activity, turnOn)
                    retroView.audioEnabled = !turnOn
                    johnnyOn = turnOn
                    gameSound = !turnOn
                }
                if (johnnyOn) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(RommSignInActivity.Panel)
                            .padding(14.dp),
                    ) {
                        Text("NOW PLAYING", color = RommSignInActivity.Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            song ?: "Picking a song…",
                            color = RommSignInActivity.TextColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        ActionButton("Skip song", Modifier.fillMaxWidth()) {
                            JohnnyMusic.skip(activity)
                            song = null
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(
        title: String,
        state: String,
        on: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(RommSignInActivity.Panel)
                .border(1.dp, if (on) RommSignInActivity.Lime else RommSignInActivity.Line, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = RommSignInActivity.TextColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(state, color = RommSignInActivity.Quiet, fontSize = 12.sp)
            }
            Box(
                Modifier
                    .size(width = 46.dp, height = 26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (on) RommSignInActivity.Lime else RommSignInActivity.Line),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(3.dp).size(20.dp).clip(RoundedCornerShape(10.dp)).background(Color.White))
            }
        }
    }

    @Composable
    private fun Bar(
        fraction: Float,
        modifier: Modifier,
    ) {
        Box(modifier.clip(RoundedCornerShape(3.dp)).background(RommSignInActivity.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(RommSignInActivity.Lime))
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(text, color = RommSignInActivity.Quiet, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.alpha(0.95f))
        }
    }
}
