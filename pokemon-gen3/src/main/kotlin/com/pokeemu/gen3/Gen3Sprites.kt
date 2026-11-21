package com.pokeemu.gen3

import com.pokeemu.core.Picture
import com.pokeemu.core.SpriteSource
import java.util.BitSet

/** Where a GBA ROM keeps its pictures (ROM offsets; -1 = not found). */
data class SpriteTables(
    val frontPics: Int = -1,
    val palettes: Int = -1,
    val shinyPalettes: Int = -1,
    /** Shiny palette entries are tagged species + this (500 in FireRed). */
    val shinyTagOffset: Int = 0,
    val itemIcons: Int = -1,
    val itemIconCount: Int = 0,
    /**
     * Non-zero for engines that keep each species' picture pointers in its species record
     * (pokeemerald-expansion): the record size. The tables above are then species 0's fields.
     */
    val speciesStride: Int = 0,
    /** Bytes per item icon entry, and where its palette pointer is after the picture pointer. */
    val itemStride: Int = 8,
    val itemPaletteDelta: Int = 4,
) {
    val hasPokemon get() = frontPics >= 0 && palettes >= 0
}

/**
 * Pokémon sprites and item icons from a GBA ROM, decoded on demand. [read]
 * returns ROM bytes at an offset (so the ROM needn't be held in memory).
 */
class Gen3Sprites(private val tables: SpriteTables, private val read: (offset: Int, length: Int) -> ByteArray?) : SpriteSource {

    override fun pokemon(species: Int, shiny: Boolean): Picture? {
        if (!tables.hasPokemon || species <= 0) return null
        if (tables.speciesStride > 0) return recordPicture(species, shiny)
        val pic = read(tables.frontPics + 8 * species, 8) ?: return null
        // Tags normally equal the species; some hacks number their newest entries differently (Radical Red).
        // Palette entries are tagged with their species, except forms that share one
        // (every Unown letter uses Unown's): the entry's position is what counts.
        val pal = if (shiny && tables.shinyPalettes >= 0) {
            read(tables.shinyPalettes + 8 * species, 8) ?: return null
        } else {
            read(tables.palettes + 8 * species, 8) ?: return null
        }
        val tiles = lz(pic.u32(0), 0x800) ?: return null
        val colours = palette(pal.u32(0)) ?: return null
        // 64x64 (8x8 tiles); some entries hold two animation frames (64x128), keep the first.
        return Picture.fromTiles(tiles, Picture.palette(colours), 8, 8)
    }

    /** Expansion engines: plain pointers in each species record, no tags. */
    private fun recordPicture(species: Int, shiny: Boolean): Picture? {
        val stride = tables.speciesStride
        fun pointer(table: Int) = read(table + stride * species, 4)?.u32(0)
        val pic = pointer(tables.frontPics) ?: return null
        val pal = pointer(if (shiny && tables.shinyPalettes >= 0) tables.shinyPalettes else tables.palettes) ?: return null
        val tiles = lz(pic, 0x800) ?: return null
        val colours = palette(pal) ?: return null
        return Picture.fromTiles(tiles, Picture.palette(colours), 8, 8)
    }

    /** A compressed palette, padded to 16 colours (newer hacks store only the colours they use). */
    private fun palette(pointer: Long): ByteArray? {
        val data = lz(pointer, 2) ?: return null
        if (data.size >= 0x20) return data
        return data.copyOf(0x20)
    }

    override fun item(item: Int): Picture? {
        if (tables.itemIcons < 0 || item <= 0 || item >= tables.itemIconCount) return null
        val at = tables.itemIcons + tables.itemStride * item
        val pic = read(at, 4)?.u32(0) ?: return null
        val pal = read(at + tables.itemPaletteDelta, 4)?.u32(0) ?: return null
        val tiles = lz(pic, 0x120) ?: return null
        val colours = lz(pal, 0x20) ?: return null
        return Picture.fromTiles(tiles, Picture.palette(colours), 3, 3)
    }

    private fun lz(pointer: Long, expected: Int): ByteArray? {
        val at = (pointer - 0x08000000L).takeIf { it >= 0 }?.toInt() ?: return null
        val head = read(at, 4) ?: return null
        if (head[0].toInt() != 0x10) return null
        val size = head.u8(1) or (head.u8(2) shl 8) or (head.u8(3) shl 16)
        if (size < expected || size > 0x8000) return null
        val data = read(at, size + size / 8 + 64) ?: read(at, minOf(size * 2, 0x10000)) ?: return null
        return Lz77.decompress(data, 0)
    }

    companion object {
        /** Finds the tables; [referenced] = offsets some ROM pointer points to. */
        internal fun scan(rom: ByteArray, referenced: BitSet, speciesCount: Int, report: MutableList<String>): SpriteTables {
            fun ptr(o: Int): Int? = (rom.u32(o) - 0x08000000L).takeIf { it in 0 until rom.size }?.toInt()
            /** Length of the run of entries 1.. whose tag at [tagAt] is index + k, with a valid pointer. */
            fun run(t: Int, tagAt: Int): Pair<Int, Int> {
                if (t + 16 > rom.size) return 0 to 0
                val k = rom.u16(t + 8 + tagAt) - 1
                var n = 0
                while (true) {
                    val e = t + 8 * (n + 1)
                    if (e + 8 > rom.size || ptr(e) == null || rom.u16(e + tagAt) - (n + 1) != k) break
                    n++
                }
                return n to k
            }
            fun lzSize(o: Int): Int = if (o + 4 <= rom.size && rom[o].toInt() == 0x10) rom.u8(o + 1) or (rom.u8(o + 2) shl 8) or (rom.u8(o + 3) shl 16) else -1

            val minimum = (speciesCount * 4 / 5).coerceIn(100, 380)
            val pics = mutableListOf<Pair<Int, Int>>() // table, length
            val pals = mutableListOf<Triple<Int, Int, Int>>() // table, length, tag offset
            var t = referenced.nextSetBit(0)
            while (t >= 0) {
                if (t % 4 == 0 && t + 16 <= rom.size) {
                    val first = ptr(t + 8)
                    if (first != null) {
                        val size = lzSize(first)
                        if (size == 0x800 || size == 0x1000) {
                            val (n, k) = run(t, 6)
                            if (n >= minimum && k == 0) pics += t to n
                        } else if (size == 0x20) {
                            val (n, k) = run(t, 4)
                            if (n >= minimum) pals += Triple(t, n, k)
                        }
                    }
                }
                t = referenced.nextSetBit(t + 1)
            }
            // Front and back tables look alike; back sprites fill the bottom of the picture.
            val front = pics.filter { it.second >= pics.maxOf { p -> p.second } * 9 / 10 }
                .minByOrNull { (table, _) -> bottomEdge(rom, table) }?.first ?: -1
            // Tables tagged with the species itself; the longest ones are the real ones.
            val plain = pals.filter { it.third == 0 }.let { l -> l.filter { it.second >= (l.maxOfOrNull { p -> p.second } ?: 0) * 9 / 10 } }
                .sortedBy { it.first }
                .distinctBy { it.first }
            val normal = plain.firstOrNull()
            val shiny = pals.filter { p ->
                p.third > 0 && (normal == null || p.first !in normal.first until normal.first + 8 * (normal.second + 1))
            }.maxByOrNull { it.second }
                // Some engines tag shiny entries with the species too (R.O.W.E.): the second such table, after the normal one.
                ?: plain.getOrNull(1)?.takeIf { s -> normal != null && s.first >= normal.first + 8 * normal.second }
            val icons = itemIcons(rom, referenced)
            val tables = SpriteTables(
                frontPics = front, palettes = normal?.first ?: -1,
                shinyPalettes = shiny?.first ?: -1, shinyTagOffset = shiny?.third ?: 0,
                itemIcons = icons?.first ?: -1, itemIconCount = icons?.second ?: 0,
            )
            report += when {
                !tables.hasPokemon -> "Could not find Pokémon sprites"
                tables.shinyPalettes < 0 -> "Found Pokémon sprites (no shiny colours)"
                else -> "Found Pokémon sprites and shiny colours"
            }
            report += if (icons != null) "Found item icons" else "Could not find item icons"
            return tables
        }

        /**
         * How much of the bottom quarter the first species' sprites fill. Back sprites
         * (seen from behind, close up) fill it far more than front sprites.
         */
        private fun bottomEdge(rom: ByteArray, table: Int): Int {
            var total = 0
            for (s in 1..40) {
                val p = (rom.u32(table + 8 * s) - 0x08000000L).toInt()
                val tiles = runCatching { Lz77.decompress(rom, p) }.getOrNull() ?: continue
                for (tile in 6 * 8 until 8 * 8) for (b in 0 until 32) { // last two rows of 8x8 tiles
                    val v = tiles.getOrNull(tile * 32 + b)?.toInt() ?: 0
                    if (v and 0x0F != 0) total++
                    if (v and 0xF0 != 0) total++
                }
            }
            return total
        }

        /** Item icon table: (pic, palette) pointer pairs to 24x24 icons; returns (table, entries). */
        private fun itemIcons(rom: ByteArray, referenced: BitSet): Pair<Int, Int>? {
            fun isIcon(o: Int): Boolean {
                if (o + 8 > rom.size) return false
                val pic = (rom.u32(o) - 0x08000000L).takeIf { it in 0 until rom.size - 4 }?.toInt() ?: return false
                val pal = (rom.u32(o + 4) - 0x08000000L).takeIf { it in 0 until rom.size - 4 }?.toInt() ?: return false
                fun size(at: Int) = if (rom[at].toInt() == 0x10) rom.u8(at + 1) or (rom.u8(at + 2) shl 8) else -1
                return size(pic) == 0x120 && size(pal) == 0x20
            }
            var best: Pair<Int, Int>? = null
            var t = referenced.nextSetBit(0)
            while (t >= 0) {
                if (t % 4 == 0 && (1..8).all { isIcon(t + 8 * it) }) {
                    var n = 1
                    while (n < 4096 && isIcon(t + 8 * n)) n++
                    if (best == null || n > best.second) best = t to n
                }
                t = referenced.nextSetBit(t + 1)
            }
            return best
        }
    }
}

/** GBA BIOS LZ77 (type 0x10). */
internal object Lz77 {
    fun decompress(b: ByteArray, at: Int): ByteArray? {
        if (at < 0 || at + 4 > b.size || b[at].toInt() != 0x10) return null
        val size = (b[at + 1].toInt() and 0xFF) or ((b[at + 2].toInt() and 0xFF) shl 8) or ((b[at + 3].toInt() and 0xFF) shl 16)
        if (size <= 0 || size > 0x40000) return null
        val out = ByteArray(size)
        var o = 0
        var i = at + 4
        while (o < size) {
            if (i >= b.size) return null
            val flags = b[i++].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                if (o >= size) break
                if (flags and (1 shl bit) != 0) {
                    if (i + 1 >= b.size) return null
                    val x = b[i].toInt() and 0xFF
                    val y = b[i + 1].toInt() and 0xFF
                    i += 2
                    val n = (x shr 4) + 3
                    val disp = (((x and 0xF) shl 8) or y) + 1
                    if (disp > o) return null
                    repeat(n) { if (o < size) { out[o] = out[o - disp]; o++ } }
                } else {
                    if (i >= b.size) return null
                    out[o++] = b[i++]
                }
            }
        }
        return out
    }
}
