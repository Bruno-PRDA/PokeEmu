package com.pokeemu.app.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.core.MoveInfo
import com.pokeemu.core.Picture
import com.pokeemu.core.SpriteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded pictures, shared by every screen (decoding a sprite takes a few ms). */
object SpriteCache {
    private val cache = LruCache<String, ImageBitmap>(400)
    private val misses = HashSet<String>()

    suspend fun get(key: String, load: () -> Picture?): ImageBitmap? {
        cache.get(key)?.let { return it }
        synchronized(misses) { if (key in misses) return null }
        val bitmap = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }?.toImageBitmap()
        if (bitmap != null) cache.put(key, bitmap) else synchronized(misses) { misses += key }
        return bitmap
    }

    fun Picture.toImageBitmap(): ImageBitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Draws a picture in a [size] box. With [unit] set, it's drawn at [size] per
 * [unit] pixels instead of fitted: DS sprites (80 px, wide margins) then
 * match GBA ones (64 px), and the extra margin is clipped.
 */
@Composable
private fun PixelImage(key: String, size: Dp, modifier: Modifier, unit: Int? = null, load: () -> Picture?) {
    val image by produceState<ImageBitmap?>(null, key) { value = SpriteCache.get(key, load) }
    Box(modifier.size(size).clipToBounds(), contentAlignment = Alignment.Center) {
        image?.let {
            val drawn = if (unit != null) size * (it.width.toFloat() / unit) else size
            Image(it, null, Modifier.requiredSize(drawn), filterQuality = FilterQuality.None)
        }
    }
}

/** A Pokémon's front sprite from its game's ROM (blank while loading or if not found). */
@Composable
fun MonSprite(sprites: SpriteSource?, species: Int, shiny: Boolean, size: Dp, modifier: Modifier = Modifier) {
    if (sprites == null || species <= 0) { Box(modifier.size(size)); return }
    PixelImage("${System.identityHashCode(sprites)}/p$species/${if (shiny) "s" else "n"}", size, modifier, unit = 64) { sprites.pokemon(species, shiny) }
}

/** An item's icon from the game's ROM. */
@Composable
fun ItemIcon(sprites: SpriteSource?, item: Int, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    if (sprites == null || item <= 0) { Box(modifier.size(size)); return }
    PixelImage("${System.identityHashCode(sprites)}/i$item", size, modifier) { sprites.item(item) }
}

/** Colours of the usual type names (hacks keep the names even when they renumber types). */
private val TYPE_COLORS = mapOf(
    "normal" to 0xFFA8A77A, "fire" to 0xFFEE8130, "water" to 0xFF6390F0, "electric" to 0xFFF7D02C,
    "grass" to 0xFF7AC74C, "ice" to 0xFF96D9D6, "fighting" to 0xFFC22E28, "poison" to 0xFFA33EA1,
    "ground" to 0xFFE2BF65, "flying" to 0xFFA98FF3, "psychic" to 0xFFF95587, "bug" to 0xFFA6B91A,
    "rock" to 0xFFB6A136, "ghost" to 0xFF735797, "dragon" to 0xFF6F35FC, "dark" to 0xFF705746,
    "steel" to 0xFFB7B7CE, "fairy" to 0xFFD685AD,
)

fun typeColor(name: String): Color = Color(TYPE_COLORS[name.lowercase()] ?: 0xFF68A090)

/** A small coloured type label. */
@Composable
fun TypeChip(name: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val bg = typeColor(name)
    Text(
        name.uppercase(),
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 1.dp else 3.dp),
        color = if (bg.luminance() > 0.6f) Color(0xFF202020) else Color.White,
        fontSize = if (compact) 9.sp else 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    )
}

@Composable
fun TypeChips(names: List<String>, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { names.distinct().forEach { TypeChip(it, compact = compact) } }
}

/** Physical / Special / Status. */
@Composable
fun CategoryBadge(category: MoveInfo.Category, modifier: Modifier = Modifier) {
    val (label, color) = when (category) {
        MoveInfo.Category.PHYSICAL -> "Physical" to Color(0xFFE8763A)
        MoveInfo.Category.SPECIAL -> "Special" to Color(0xFF5B7FE8)
        MoveInfo.Category.STATUS -> "Status" to Color(0xFF9A9A9A)
    }
    Text(
        label,
        modifier = modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** "95 power · 100% · 15 PP", leaving out what doesn't apply. */
fun MoveInfo.summary(): String = listOfNotNull(
    power.takeIf { it > 1 }?.let { "$it power" },
    if (accuracy == 0) "never misses" else "$accuracy%",
    "$pp PP",
).joinToString(" · ")

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue

/** A rounded tile behind sprites, so they read as a group. */
@Composable
fun SpriteTile(size: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) { content() }
}
