package com.pokeemu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/** A 5x7 pixel font (capitals, digits and the punctuation game names use). */
private val GLYPHS: Map<Char, List<String>> = mapOf(
    'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
    'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
    'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
    'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
    'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
    'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
    'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'I' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
    'J' to listOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
    'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
    'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
    'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
    'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
    'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
    'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
    'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
    'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
    'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
    'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
    'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
    'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
    'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
    '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
    '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6' to listOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
    '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
    '.' to listOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
    ',' to listOf(".....", ".....", ".....", ".....", ".##..", "..#..", ".#..."),
    '-' to listOf(".....", ".....", ".....", ".###.", ".....", ".....", "....."),
    '\'' to listOf("..#..", "..#..", ".#...", ".....", ".....", ".....", "....."),
    ':' to listOf(".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."),
    '!' to listOf("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
    '?' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
    '&' to listOf(".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#"),
    '/' to listOf("....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."),
    '(' to listOf("...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."),
    ')' to listOf(".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."),
    '+' to listOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
    ' ' to listOf(".....", ".....", ".....", ".....", ".....", ".....", "....."),
)

/** Upper case, accents dropped, unknown characters as spaces. */
private fun pixelChars(text: String): String =
    Normalizer.normalize(text.uppercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .map { if (it in GLYPHS) it else ' ' }.joinToString("").trim()

/**
 * [text] drawn in a pixel font, like a game's title: a vertical gradient
 * from [top] to [bottom] with a dark outline-shadow. Pixels are [pixel] big,
 * or smaller so the whole text fits the available width.
 */
@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    pixel: Dp = 3.dp,
    top: Color = Color(0xFFFFF3B0),
    bottom: Color = Color(0xFFFFB13B),
    shadow: Color = Color(0xFF1A1206),
) {
    val chars = pixelChars(text).ifEmpty { "?" }
    val columns = chars.length * 6 - 1 + 1 // 5 wide, 1 apart, +1 for the shadow
    val rows = 7 + 1
    BoxWithConstraints(modifier) {
        val p = minOf(pixel, maxWidth / columns)
        Canvas(Modifier.width(p * columns).height(p * rows)) {
            val px = p.toPx()
            fun cells(draw: (x: Int, y: Int) -> Unit) {
                chars.forEachIndexed { i, c ->
                    val g = GLYPHS.getValue(c)
                    for (y in 0 until 7) for (x in 0 until 5) if (g[y][x] == '#') draw(i * 6 + x, y)
                }
            }
            cells { x, y -> drawRect(shadow, Offset((x + 1) * px, (y + 1) * px), Size(px + 0.5f, px + 0.5f)) }
            cells { x, y -> drawRect(lerp(top, bottom, y / 6f), Offset(x * px, y * px), Size(px + 0.5f, px + 0.5f)) }
        }
    }
}
