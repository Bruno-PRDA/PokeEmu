package com.pokeemu.gen4

import com.pokeemu.core.Picture
import com.pokeemu.core.SpriteSource

/**
 * Pokémon sprites and item icons from a DS Pokémon ROM.
 *
 * Sprites: an archive with a fixed number of files per species (pictures,
 * then normal and shiny palettes). Vanilla and most hacks use a/0/0/4 with 6
 * per species; some engines keep them in data/pokepic_m.narc with 8. Pictures
 * are 160x80 (two 80x80 frames), 4-bit, scrambled with a simple cipher whose
 * direction differs between Diamond/Pearl and later games.
 *
 * Item icons: picture/palette pairs in a/0/1/8, found through the game
 * code's item table (item, icon file, palette file, GBA item number).
 */
class Gen4Sprites private constructor(
    private val rom: RomSource,
    private val fs: NdsFileSystem,
    private val spriteArchive: String?,
    private val filesPerSpecies: Int,
    private val itemTable: ByteArray?,
) : SpriteSource {
    private val narcs = HashMap<String, LazyNarc?>()
    /** Archives are read piece by piece: a sprite archive can be 50 MB. */
    private fun narc(path: String): LazyNarc? = synchronized(narcs) {
        narcs.getOrPut(path) { runCatching { fs.openNarc(path) }.getOrNull() }
    }

    override fun pokemon(species: Int, shiny: Boolean): Picture? = synchronized(this) { pokemonLocked(species, shiny) }

    private fun pokemonLocked(species: Int, shiny: Boolean): Picture? {
        val narc = narc(spriteArchive ?: return null) ?: return null
        val base = species * filesPerSpecies
        if (base + filesPerSpecies > narc.size) return null
        val layout = LAYOUTS.getValue(filesPerSpecies)
        val palette = nclrPalette(narc[base + if (shiny) layout.shinyPalette else layout.palette]) ?: return null
        // The male (or only) front picture: the first usable front entry.
        for (i in layout.fronts) {
            val pixels = ncgrPixels(narc[base + i]) ?: continue
            val pic = decrypt(pixels, palette) ?: continue
            return Picture(80, 80, IntArray(80 * 80) { pic.argb[(it / 80) * 160 + it % 80] })
        }
        return null
    }

    override fun item(item: Int): Picture? = synchronized(this) { itemLocked(item) }

    private fun itemLocked(item: Int): Picture? {
        val narc = ITEM_ICONS.firstNotNullOfOrNull(::narc) ?: return null
        val (icon, pal) = if (itemTable != null) {
            if (8 * item + 8 > itemTable.size) return null
            itemTable.u16(8 * item + 2) to itemTable.u16(8 * item + 4)
        } else {
            // No table (some engines rebuild the icons): item N's picture and palette follow the
            // two header files and "no item": files 2N+2 and 2N+3.
            if (item <= 0) return null
            (2 * item + 2) to (2 * item + 3)
        }
        if (icon >= narc.size || pal >= narc.size) return null
        val pixels = ncgrPixels(narc[icon]) ?: return null
        val palette = nclrPalette(narc[pal]) ?: return null
        return Picture.fromTiles(pixels, palette, 4, 4)
    }

    /** Tries each cipher direction (and none); keeps the result with a clean transparent border. */
    private fun decrypt(data: ByteArray, palette: IntArray): Picture? {
        val candidates = listOf(data, decipher(data, forward = true), decipher(data, forward = false))
        return candidates.map { Picture.fromLinear(it, palette, 160, 80) }
            .maxByOrNull(::borderScore)
            ?.takeIf { borderScore(it) > 0.6 }
    }

    private fun borderScore(p: Picture): Double {
        var clear = 0; var total = 0
        for (x in 0 until p.width) { total += 2; if (p.argb[x] == 0) clear++; if (p.argb[(p.height - 1) * p.width + x] == 0) clear++ }
        for (y in 0 until p.height) { total += 2; if (p.argb[y * p.width] == 0) clear++; if (p.argb[y * p.width + p.width - 1] == 0) clear++ }
        return clear.toDouble() / total
    }

    companion object {
        private val SPRITE_ARCHIVES = listOf(
            "a/0/0/4" to 6, "poketool/pokegra/pokegra.narc" to 6, "poketool/pokegra/pl_pokegra.narc" to 6,
            "data/pokepic_m.narc" to 8,
        )
        /** Per-species file layout: front pictures (preferred first), normal and shiny palette. */
        private class Layout(val fronts: List<Int>, val palette: Int, val shinyPalette: Int)
        private val LAYOUTS = mapOf(
            // back female, back male, front female, front male, palette, shiny palette
            6 to Layout(fronts = listOf(3, 2), palette = 4, shinyPalette = 5),
            // (front, palette), (back, palette), (shiny front, shiny palette), (shiny back, shiny palette)
            8 to Layout(fronts = listOf(0), palette = 1, shinyPalette = 5),
        )
        /** HeartGold/SoulSilver, then Diamond/Pearl/Platinum. */
        private val ITEM_ICONS = listOf("a/0/1/8", "itemtool/itemdata/item_icon.narc")

        fun open(rom: RomSource): Gen4Sprites {
            val fs = NdsFileSystem(rom)
            var archive: String? = null
            var per = 6
            for ((path, n) in SPRITE_ARCHIVES) {
                val narc = runCatching { fs.openNarc(path) }.getOrNull() ?: continue
                if (narc.size < 400 * n) continue
                archive = path; per = n
                break
            }
            return Gen4Sprites(rom, fs, archive, per, itemTable(rom))
        }

        /** The ARM9 item table: 4 u16 per item (data, icon, palette, GBA item), item 1 = (1, 2, 3, 1). */
        private fun itemTable(rom: RomSource): ByteArray? {
            val arm9 = Arm9.read(rom)
            val pattern = byteArrayOf(1, 0, 2, 0, 3, 0, 1, 0)
            var i = 8
            while (i + 8 <= arm9.size) {
                if ((0 until 8).all { arm9[i + it] == pattern[it] } && arm9.u16(i + 8) == 2 && arm9.u16(i + 10) == 4) {
                    val start = i - 8
                    var n = 0
                    while (start + 8 * (n + 1) <= arm9.size && arm9.u16(start + 8 * n + 2) in 2 until 4096 && n < 2048) n++
                    return arm9.copyOfRange(start, start + 8 * n)
                }
                i += 2
            }
            return null
        }

        /** The pixel data of an NCGR ("RGCN") file. */
        internal fun ncgrPixels(b: ByteArray): ByteArray? {
            if (b.size < 0x30 || b[0] != 'R'.code.toByte() || b[1] != 'G'.code.toByte()) return null
            val size = b.u32(0x28).toInt()
            val offset = 0x18 + b.u32(0x2C).toInt()
            if (size <= 0 || offset + size > b.size) return null
            return b.copyOfRange(offset, offset + size)
        }

        /** The first 16 colours of an NCLR ("RLCN") file. */
        internal fun nclrPalette(b: ByteArray): IntArray? {
            if (b.size < 0x28 + 32 || b[0] != 'R'.code.toByte() || b[1] != 'L'.code.toByte()) return null
            return Picture.palette(b, 0x28)
        }

        /** The DS games' sprite cipher: XOR with an LCG keyed by the first (or last) word. */
        internal fun decipher(data: ByteArray, forward: Boolean): ByteArray {
            val out = data.copyOf()
            val words = data.size / 2
            if (words == 0) return out
            var seed = (if (forward) data.u16(0) else data.u16(2 * (words - 1))).toLong()
            val range = if (forward) 0 until words else (words - 1) downTo 0
            for (w in range) {
                val v = data.u16(2 * w) xor (seed.toInt() and 0xFFFF)
                out[2 * w] = v.toByte(); out[2 * w + 1] = (v shr 8).toByte()
                seed = (seed * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
            }
            return out
        }
    }
}
