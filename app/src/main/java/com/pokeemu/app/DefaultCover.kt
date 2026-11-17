package com.pokeemu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.pokeemu.app.ui.typeColor
import com.pokeemu.core.GameData
import com.pokeemu.core.SpriteSource
import com.pokeemu.gen3.Gen3Sprites
import com.pokeemu.gen4.FileRomSource
import com.pokeemu.gen4.Gen4Sprites
import com.pokeemu.saves.GameStorage
import com.pokeemu.saves.Platform
import java.io.File
import java.io.RandomAccessFile

/**
 * A game's picture when the player hasn't chosen one: its mascot Pokémon,
 * drawn from the ROM's own sprite, on a glow in the Pokémon's type colour.
 * Hacks keep their base game's code, so they get its mascot (unless the hack
 * renamed it away, then Pikachu). Made once and kept in games/<id>/.
 */
object DefaultCover {
    private const val FILE = "default-cover-v1.png"
    private const val SIZE = 240

    /** Mascots by game code (first three letters). */
    private val MASCOTS = mapOf(
        "BPR" to "Charizard", "BPG" to "Venusaur", "AXV" to "Groudon", "AXP" to "Kyogre", "BPE" to "Rayquaza",
        "ADA" to "Dialga", "APA" to "Palkia", "CPU" to "Giratina", "IPK" to "Ho-Oh", "IPG" to "Lugia",
    )

    /** The cached picture, or a new one (slow the first time: reads the ROM). Null when it can't be drawn. */
    fun get(context: Context, rom: RomEntry): File? = synchronized(this) { // one at a time: each may read a 32 MB ROM
        if (rom.id.isEmpty() || !rom.header.pokemonTools) return@synchronized null
        val storage = GameStorage(context.filesDir)
        val out = File(storage.gameDir(rom.id), FILE)
        if (out.isFile) return@synchronized out
        val bitmap = runCatching { draw(context, rom, storage) }.getOrNull() ?: return@synchronized null
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out
    }

    private fun draw(context: Context, rom: RomEntry, storage: GameStorage): Bitmap? {
        val local = RomLibrary(context).localCopy(rom.uri)
        val store = RomDataStore(storage)
        val (data: GameData?, sprites: SpriteSource?) = if (rom.header.platform == Platform.NDS) {
            store.gen4(local.id, local.file) to Gen4Sprites.open(FileRomSource(local.file))
        } else {
            val data = store.cached(local.id) ?: store.scanAndCache(local.id, local.file.readBytes())
            data to data.sprites.takeIf { it.hasPokemon }?.let { tables -> Gen3Sprites(tables) { o, n -> readAt(local.file, o, n) } }
        }
        if (data == null || sprites == null) return null
        val names = listOfNotNull(MASCOTS[rom.header.gameCode.take(3)], "Pikachu")
        val species = names.firstNotNullOfOrNull { n -> data.speciesNames.indexOfFirst { it.equals(n, ignoreCase = true) }.takeIf { it > 0 } }
            ?: return null
        val picture = sprites.pokemon(species, shiny = false)?.trimmed() ?: return null
        val type = data.base(species)?.type1?.let(data::typeName) ?: "Normal"
        val colour = typeColor(type).toArgb()

        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(darken(colour, 0.35f))
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(SIZE / 2f, SIZE * 0.42f, SIZE * 0.7f, colour, darken(colour, 0.35f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), this)
        }
        // Pixel art: whole-number scale, no smoothing, a bit above the middle (the name sits at the bottom).
        val sprite = Bitmap.createBitmap(picture.argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)
        val scale = maxOf(1, minOf((SIZE * 0.72f / picture.width).toInt(), (SIZE * 0.62f / picture.height).toInt()))
        val w = picture.width * scale; val h = picture.height * scale
        val left = (SIZE - w) / 2; val top = ((SIZE * 0.44f) - h / 2f).toInt().coerceAtLeast(8)
        canvas.drawBitmap(sprite, null, Rect(left, top, left + w, top + h), Paint().apply { isFilterBitmap = false })
        return bmp
    }

    private fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun readAt(file: File, offset: Int, length: Int): ByteArray? = RandomAccessFile(file, "r").use { f ->
        if (offset < 0 || offset >= f.length()) return@use null
        ByteArray(minOf(length.toLong(), f.length() - offset).toInt()).also { f.seek(offset.toLong()); f.readFully(it) }
    }
}
