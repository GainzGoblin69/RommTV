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

/** A little pixel-art trash can, drawn square by square so it stays crisp at any size. */
private val TrashPixels =
    listOf(
        ".....KKKK.....",
        "....KLLLLK....",
        ".KKKKKKKKKKKK.",
        "KLLLLLLLLLLLMK",
        "KMMMMMMMMMMMDK",
        ".KKKKKKKKKKKK.",
        ".KDMSMMSMMSLK.",
        ".KDMSMMSMMSLK.",
        ".KDMSMMSMMSLK.",
        ".KDMSMMSMMSLK.",
        ".KDMSMMSMMSLK.",
        "..KDSMMSMMSK..",
        "..KDSMMSMMSK..",
        "..KDSMMSMMSK..",
        "..KDMMMMMMMK..",
        "...KKKKKKKK...",
    )

private val TrashColors =
    mapOf(
        'K' to Color(0xFF0B0F15),
        'L' to Color(0xFFD3DEE9),
        'M' to Color(0xFF93A7BC),
        'D' to Color(0xFF5B6E84),
        'S' to Color(0xFF26323F),
    )

@Composable
fun TrashIcon(
    height: Dp = 24.dp,
    description: String = "Remove",
    modifier: Modifier = Modifier,
) {
    val rows = TrashPixels.size
    val cols = TrashPixels.first().length
    Canvas(modifier.size(height * cols / rows, height).semantics { contentDescription = description }) {
        val cell = size.height / rows
        TrashPixels.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val color = TrashColors[c] ?: return@forEachIndexed
                // A hair of overlap so no seams show between squares
                drawRect(color, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
