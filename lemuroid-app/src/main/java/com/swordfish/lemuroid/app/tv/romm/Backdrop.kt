package com.swordfish.lemuroid.app.tv.romm

import android.content.Context
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.romm.RommClient
import com.swordfish.lemuroid.lib.romm.RommSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A game's screenshots behind the menus: soft, dark and slow, so the screen keeps its colours and stays easy to
 * read. Each image is loaded tiny and stretched (a natural blur on every TV; Android 12+ blurs it a bit more),
 * dimmed with the app's background colour, and faded to it at the bottom and left where the content sits.
 */
@Composable
fun ScreenshotBackdrop(
    shots: List<List<String>>,
    modifier: Modifier = Modifier,
    background: Color,
    secondsEach: Int = 12,
) {
    val context = LocalContext.current
    var failed by remember(shots) { mutableStateOf(setOf<Int>()) }
    var index by remember(shots) { mutableStateOf(0) }
    val usable = shots.indices.filter { it !in failed }

    LaunchedEffect(shots, usable.size) {
        if (usable.size < 2) return@LaunchedEffect
        while (true) {
            delay(secondsEach * 1000L)
            val position = usable.indexOf(index)
            index = usable[(position + 1) % usable.size]
        }
    }

    Box(modifier.fillMaxSize().background(background)) {
        Crossfade(targetState = shots.getOrNull(index)?.takeIf { index !in failed }, animationSpec = tween(1200), label = "backdrop") { urls ->
            if (urls != null) {
                var candidate by remember(urls) { mutableStateOf(0) }
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(urls[candidate.coerceAtMost(urls.lastIndex)])
                            .size(192, 108)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.55f,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(16.dp) else Modifier),
                    onError = {
                        if (candidate < urls.lastIndex) {
                            candidate++
                        } else {
                            failed = failed + shots.indexOf(urls)
                            usable.firstOrNull { it != index && it !in failed }?.let { index = it }
                        }
                    },
                )
            }
        }
        // Dim everything with the app's colour, then fade fully into it where the menus are
        Box(Modifier.fillMaxSize().background(background.copy(alpha = 0.57f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to background.copy(alpha = 0.35f), 0.45f to Color.Transparent, 1f to background),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to background.copy(alpha = 0.7f), 0.5f to Color.Transparent, 1f to background.copy(alpha = 0.3f)),
            ),
        )
    }
}

/** Screenshots for the backdrop, by game, fetched from RomM once and kept for the app's lifetime. */
object BackdropShots {
    private val cache = ConcurrentHashMap<String, List<List<String>>>()

    fun cached(game: Game): List<List<String>>? = cache[game.fileUri]

    suspend fun forGame(
        context: Context,
        game: Game,
    ): List<List<String>> {
        cache[game.fileUri]?.let { return it }
        val romId = RommSync.romId(game)
        val shots =
            withContext(Dispatchers.IO) {
                runCatching {
                    val client = RommClient(RommSession(context.applicationContext))
                    romId?.let { id -> client.romDetail(id).screenshots.map { client.imageCandidates(it) } }.orEmpty()
                }.getOrDefault(emptyList())
            }
        // No screenshots: the cover art works too, since it's softened just the same
        val result = shots.ifEmpty { listOfNotNull(game.coverFrontUrl?.let { listOf(it) }) }
        cache[game.fileUri] = result
        return result
    }
}
