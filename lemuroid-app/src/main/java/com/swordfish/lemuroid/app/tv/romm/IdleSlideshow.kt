package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Notices when nobody has pressed anything for a while, so the screenshot slideshow can start.
 * Activities pass every key and touch through [consumeKey] / [consumeTouch] first: when the slideshow is up,
 * the press that ends it is swallowed so it doesn't also click whatever was selected.
 */
object Idle {
    private val _lastInput = MutableStateFlow(SystemClock.uptimeMillis())
    val lastInput: StateFlow<Long> = _lastInput

    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing

    private var swallowKey: Int? = null

    fun touch() {
        _lastInput.value = SystemClock.uptimeMillis()
    }

    fun show() {
        _showing.value = true
    }

    fun consumeKey(event: KeyEvent): Boolean {
        touch()
        if (swallowKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = null
            return true
        }
        if (_showing.value) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                _showing.value = false
                swallowKey = event.keyCode
            }
            return true
        }
        return false
    }

    fun consumeTouch(event: MotionEvent): Boolean {
        touch()
        if (_showing.value) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) _showing.value = false
            return true
        }
        return false
    }
}

/** One picture in the slideshow: candidate addresses for it, and which game it's from. */
data class Slide(val urls: List<String>, val title: String, val platform: String)

/** Random screenshots from across the library, for the slideshow. */
class SlideSource(private val context: Context, private val db: RetrogradeDatabase) {
    private var games: List<Game>? = null

    private fun platformName(game: Game): String =
        runCatching { context.getString(GameSystem.findById(game.systemId).shortTitleResId) }.getOrDefault(game.systemId.uppercase())

    fun slideFor(
        game: Game,
        urls: List<String>,
    ) = Slide(urls, game.title, platformName(game))

    /** A random screenshot from a random game, already downloaded so it appears without a gap. Null if none can be found. */
    suspend fun next(): Slide? {
        val all = games ?: withContext(Dispatchers.IO) { db.gameDao().listAll() }.also { games = it }
        if (all.isEmpty()) return null
        repeat(10) {
            val game = all.random()
            val shots = BackdropShots.forGame(context, game).filter { it.firstOrNull() != game.coverFrontUrl }
            val shot = shots.randomOrNull() ?: return@repeat
            for (url in shot) {
                val ok = context.imageLoader.execute(ImageRequest.Builder(context).data(url).build()) is SuccessResult
                if (ok) return Slide(listOf(url), game.title, platformName(game))
            }
        }
        return null
    }
}

/**
 * Starts the slideshow after the idle time set in Settings → Menu music (2 minutes unless changed),
 * and shows it over the screen. [start] is the picture currently behind the menus, so it begins from there.
 */
@Composable
fun IdleSlideshowHost(
    start: Slide?,
    source: SlideSource,
) {
    val context = LocalContext.current
    val last by Idle.lastInput.collectAsState()
    val showing by Idle.showing.collectAsState()
    val minutes = remember(last) { MenuMusic.settings(context).idleMinutes }
    LaunchedEffect(last, minutes) {
        if (minutes <= 0) return@LaunchedEffect
        delay(minutes * 60_000L)
        Idle.show()
    }
    AnimatedVisibility(visible = showing, enter = fadeIn(tween(1500)), exit = fadeOut(tween(500))) {
        Slideshow(start, source)
    }
}

@Composable
private fun Slideshow(
    start: Slide?,
    source: SlideSource,
) {
    var slide by remember { mutableStateOf(start) }
    LaunchedEffect(Unit) {
        if (slide == null) slide = source.next()
        while (true) {
            val upcoming = source.next()
            delay(9_000)
            if (upcoming != null) slide = upcoming
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = slide, animationSpec = tween(1800), label = "slideshow") { s ->
            if (s != null) SlidePicture(s)
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            slide?.let {
                Column(Modifier.weight(1f)) {
                    Text(it.platform.uppercase(), color = Color(0xFF8FDC4E), fontSize = 13.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
                    Text(it.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(24.dp))
            NowPlayingChip()
        }
    }
}

@Composable
private fun SlidePicture(slide: Slide) {
    val context = LocalContext.current
    var candidate by remember(slide) { mutableStateOf(0) }
    val url = slide.urls.getOrNull(candidate) ?: return
    Box(Modifier.fillMaxSize()) {
        // The same picture, stretched and softened, fills the sides; the picture itself is shown whole and sharp
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).size(160, 90).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.45f,
            modifier = Modifier.fillMaxSize(),
        )
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onError = { candidate++ },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xCC000000)),
            ),
        )
    }
}

/**
 * "♪ Song · Album" for the menu music; nothing when no music is playing.
 * On a phone or tablet, tapping it skips to the next song (on a TV it stays out of the way of the D-pad).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NowPlayingChip(modifier: Modifier = Modifier) {
    val now by MenuMusic.nowPlaying.collectAsState()
    val playing = now ?: return
    val context = LocalContext.current
    val touch = remember { !com.swordfish.lemuroid.lib.romm.Device.isTv(context) }
    Row(
        modifier
            .widthIn(max = 420.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC161C26))
            .then(if (touch) Modifier.clickable { MenuMusic.skip() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing.coverUrl != null) {
            val context = LocalContext.current
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(playing.coverUrl)
                        .apply { MenuMusic.authHeaders(MenuMusic.settings(context)).forEach { (k, v) -> addHeader(k, v) } }
                        .size(96, 96)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.width(9.dp))
        } else {
            Text("♪", color = Color(0xFF8FDC4E), fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
        }
        // Fixed height; names longer than the space scroll sideways
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
            Text(
                playing.title,
                color = Color(0xFFE8EDF3),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                playing.album,
                color = Color(0xFFA9B4C2),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
        }
        if (touch) {
            Spacer(Modifier.width(10.dp))
            Text("⏭", color = Color(0xFF8FDC4E), fontSize = 16.sp)
        }
    }
}
