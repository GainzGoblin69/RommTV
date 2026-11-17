package com.swordfish.lemuroid.app.tv.romm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Little pixel-art pictures, drawn square by square from text grids (one letter per square, "." is empty),
 * so they stay crisp at any size.
 */
@Composable
fun PixelArt(
    rows: List<String>,
    colors: Map<Char, Color>,
    height: Dp,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val cols = rows.first().length
    val sized = modifier.size(height * cols / rows.size, height)
    Canvas(if (description != null) sized.semantics { contentDescription = description } else sized) {
        val cell = size.height / rows.size
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val color = colors[c] ?: return@forEachIndexed
                // A hair of overlap so no seams show between squares
                drawRect(color, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

private val Outline = Color(0xFF0B0F15)

/** A swing-lid bin. */
private val TrashPixels =
    listOf(
        ".......KKKKKKKK.......",
        "......KMLLLLLLLK......",
        ".....KDDMMMMMMMLK.....",
        "....KDDTTTTTTTTMLK....",
        "....KDDSSSSSSSTMLK....",
        "...KDDMSSSSSSSTMMLK...",
        "...KDDMSSSSSSSTMMLK...",
        "...KDDMSTTTTTTTMMLK...",
        "..KKDDMMMMMMMMMMMLKK..",
        ".KDMLLLLLLLLLLLLLLLLK.",
        ".KDDDDDDDDDDDDDDDDMMK.",
        "..KSSSSSSSSSSSSSSSSK..",
        "..KDDDDNMMMMMMMMMLLK..",
        "..KDDDDNMMMMMMMMMLLK..",
        "..KDDDDNMMMWWMMMMLLK..",
        "..KDDDDNMMWMMWMMMLLK..",
        "...KDDDDNWWMMWWMLLK...",
        "...KDDDDNMMMMMMMLLK...",
        "...KDDDDWMMWWMMWLLK...",
        "...KDDDDWWMWWMWWLLK...",
        "...KDDDDNWWWWWWMLLK...",
        "....KDDDDNMMMMMLLK....",
        "....KDDDDNMMMMMLLK....",
        "....KDDDDNMMMMMLLK....",
        "....KDDDDNMMMMMLLK....",
        ".....KDDDDDDDDDDK.....",
        "......KKKKKKKKKK......",
    )

private val TrashColors =
    mapOf(
        'K' to Outline,
        'L' to Color(0xFFD6E0EB),
        'M' to Color(0xFF8EA0B8),
        'N' to Color(0xFF7689A3),
        'D' to Color(0xFF566882),
        'S' to Color(0xFF283446),
        'T' to Color(0xFF3E4E64),
        'W' to Color(0xFFE2EAF3),
    )

@Composable
fun TrashIcon(
    height: Dp = 26.dp,
    description: String = "Remove",
    modifier: Modifier = Modifier,
) {
    PixelArt(TrashPixels, TrashColors, height, modifier, description)
}

/** A game controller. */
private val PadPixels =
    listOf(
        "....KKKKK........KKKKK....",
        "...KBBBBBKKKKKKKKBBBBBK...",
        "..KBLLLLLBBBBBBBBLLLLLBK..",
        ".KBLLLLLLLLLLLLLLLLLLLLBK.",
        "KBBBBDBBBBBBBBBBBBBBABBBBK",
        "KBBBDDDBBBSSBBSSBBBABABBBK",
        "KBBBBDBBBBBBBBBBBBBBABBBBK",
        "KBBBBBBBGGGBBBBGGGBBBBBBBK",
        "KBBBBBBGGHGGBBGGHGGBBBBBBK",
        "KBBBBBBBGGGBBBBGGGBBBBBBBK",
        "KHBBBBBHHHHHHHHHHHHBBBBBHK",
        "KHBBBBHKKKKKKKKKKKKHBBBBHK",
        "KHBBBBHK..........KHBBBBHK",
        ".KHBBHK............KHBBHK.",
        "..KHHK..............KHHK..",
        "...KK................KK...",
    )

/** "P1" to "P4", each with a little arrow pointing down at its controller. */
private val PlayerLabels =
    mapOf(
        1 to
        listOf(
            ".KKKK.....K...",
            "KXXXXK...KXK..",
            "KXKKKXK.KXXK..",
            "KXKKKXK..KXK..",
            "KXXXXK...KXK..",
            "KXKKK....KXK..",
            "KXK......KXK..",
            "KXK.....KXXXK.",
            ".K.KKKKKKKKK..",
            "..KXXXXXXXXK..",
            "...KXXXXXXK...",
            "....KXXXXK....",
            ".....KXXK.....",
            "......KK......",
        ),
        2 to
        listOf(
            ".KKKK....KKK..",
            "KXXXXK..KXXXK.",
            "KXKKKXKKXKKKXK",
            "KXKKKXK.K..KXK",
            "KXXXXK....KXK.",
            "KXKKK....KXK..",
            "KXK.....KXKKK.",
            "KXK....KXXXXXK",
            ".K.KKKKKKKKKK.",
            "..KXXXXXXXXK..",
            "...KXXXXXXK...",
            "....KXXXXK....",
            ".....KXXK.....",
            "......KK......",
        ),
        3 to
        listOf(
            ".KKKK...KKKK..",
            "KXXXXK.KXXXXK.",
            "KXKKKXK.KKKKXK",
            "KXKKKXK..KKKXK",
            "KXXXXK..KXXXK.",
            "KXKKK....KKKXK",
            "KXK.....KKKKXK",
            "KXK....KXXXXK.",
            ".K.KKKKKKKKK..",
            "..KXXXXXXXXK..",
            "...KXXXXXXK...",
            "....KXXXXK....",
            ".....KXXK.....",
            "......KK......",
        ),
        4 to
        listOf(
            ".KKKK......K..",
            "KXXXXK....KXK.",
            "KXKKKXK..KXXK.",
            "KXKKKXK.KXKXK.",
            "KXXXXK.KXKKXK.",
            "KXKKK..KXXXXXK",
            "KXK.....KKKXK.",
            "KXK.......KXK.",
            ".K.KKKKKKKKK..",
            "..KXXXXXXXXK..",
            "...KXXXXXXK...",
            "....KXXXXK....",
            ".....KXXK.....",
            "......KK......",
        ),
    )

/** Player 1 gets a light controller, the others dark ones; each has buttons in its player colour. */
private fun padColors(
    light: Boolean,
    accent: Color,
) = mapOf(
    'K' to Outline,
    'B' to if (light) Color(0xFFDEE4EC) else Color(0xFF3A4250),
    'L' to if (light) Color(0xFFF5F8FC) else Color(0xFF566070),
    'H' to if (light) Color(0xFFAAB4C2) else Color(0xFF242A36),
    'D' to if (light) Color(0xFF28303C) else Color(0xFF96A0AF),
    'S' to Color(0xFF5A6474),
    'G' to if (light) Color(0xFF606A7A) else Color(0xFF6E7888),
    'A' to accent,
)

@Composable
fun PlayerPadIcon(
    number: Int,
    accent: Color,
    height: Dp = 26.dp,
    modifier: Modifier = Modifier,
) {
    PixelArt(PadPixels, padColors(number == 1, accent), height, modifier)
}

@Composable
fun PlayerLabelIcon(
    number: Int,
    accent: Color,
    height: Dp = 22.dp,
) {
    val rows = PlayerLabels[number.coerceIn(1, 4)] ?: return
    PixelArt(rows, mapOf('K' to Outline, 'X' to accent), height)
}
