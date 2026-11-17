package com.pokeemu.gen3

import com.pokeemu.core.Learnsets
import java.util.BitSet

/**
 * Hacks built on pokeemerald-expansion (Emerald Rogue, The Pit, R.O.W.E....)
 * keep everything about a species in one record: stats, name, and pointers
 * to its pictures, moves and evolutions. The record's layout changes between
 * versions, so each pointer field is recognised by what it points to.
 *
 * [base] is species 0's record, [stride] the record size.
 */
internal class ExpansionSpecies(
    private val rom: ByteArray,
    private val base: Int,
    private val stride: Int,
    private val speciesCount: Int,
    private val moveCount: Int,
) {
    /** Species every version has, with known evolutions: Bulbasaur, Charmander, Squirtle, Pidgey, Eevee. */
    private val samples = listOf(1, 4, 7, 16, 133)

    private fun ptr(o: Int): Int? =
        if (o < 0 || o + 4 > rom.size) null else (rom.u32(o) - 0x08000000L).takeIf { it in 0 until rom.size }?.toInt()

    private fun field(species: Int, off: Int): Int? = ptr(base + stride * species + off)
    private fun u16(o: Int): Int? = if (o >= 0 && o + 2 <= rom.size) rom.u16(o) else null
    private fun lzSize(o: Int): Int =
        if (o + 4 <= rom.size && rom[o].toInt() == 0x10) rom.u8(o + 1) or (rom.u8(o + 2) shl 8) or (rom.u8(o + 3) shl 16) else -1

    private val offsets = (0..stride - 4 step 4).toList()

    /** Offsets of pointer fields whose target passes [test] for every sample species. */
    private fun fields(test: (Int) -> Boolean): List<Int> =
        offsets.filter { o -> samples.all { s -> field(s, o)?.let(test) == true } }

    // ---- pictures ---------------------------------------------------------------------

    fun sprites(): SpriteTables? {
        val pics = fields { lzSize(it) == 0x800 || lzSize(it) == 0x1000 }
        val pals = fields { lzSize(it) == 0x20 }
        if (pics.isEmpty() || pals.isEmpty()) return null
        // Front and back pictures look alike; back pictures fill the bottom far more.
        val front = pics.minByOrNull { o -> field(1, o)?.let(::bottomFill) ?: Int.MAX_VALUE }!!
        return SpriteTables(
            frontPics = base + front,
            palettes = base + pals[0],
            shinyPalettes = pals.getOrNull(1)?.let { base + it } ?: -1,
            speciesStride = stride,
        )
    }

    /** Non-transparent pixels in the bottom quarter of a 64x64 picture (tiles 48..63). */
    private fun bottomFill(at: Int): Int {
        val tiles = Lz77.decompress(rom, at) ?: return Int.MAX_VALUE
        var n = 0
        for (i in 48 * 32 until minOf(64 * 32, tiles.size)) {
            val b = tiles[i].toInt()
            if (b and 0x0F != 0) n++
            if (b and 0xF0 != 0) n++
        }
        return n
    }

    // ---- moves and evolutions ------------------------------------------------------------

    /** { u16 move; u16 level }, ending with move 0xFFFF. */
    private fun levelUpAt(p: Int): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until 128) {
            val move = u16(p + 4 * i) ?: return null
            if (move == 0xFFFF) return out.takeIf { it.isNotEmpty() }
            val level = u16(p + 4 * i + 2) ?: return null
            if (move == 0 || move >= moveCount || level > 101) return null
            out += level to move
        }
        return null
    }

    /** u16 moves, ending with 0xFFFF (or 0). */
    private fun moveListAt(p: Int): List<Int>? {
        val out = ArrayList<Int>()
        for (i in 0 until 400) {
            val move = u16(p + 2 * i) ?: return null
            if (move == 0xFFFF || move == 0) return out
            if (move >= moveCount) return null
            out += move
        }
        return null
    }

    /** Evolution entries of [entrySize] bytes: u16 method, u16 parameter, u16 target; method 0xFFFF ends. */
    private fun evolutionsAt(p: Int, entrySize: Int): List<Int>? {
        val out = ArrayList<Int>()
        for (i in 0 until 16) {
            val method = u16(p + entrySize * i) ?: return null
            if (method == 0xFFFF || method == 0) return out
            val target = u16(p + entrySize * i + 4) ?: return null
            if (target == 0 || target >= speciesCount) return null
            out += target
        }
        return null
    }

    /** What the species records (or the engine's per-species tables) hold; empty parts weren't found. */
    class Parts(val levelUp: List<List<Pair<Int, Int>>>, val evolutions: List<List<Int>>, val teachable: List<List<Int>>?)

    fun learnsets(referenced: BitSet): Parts {
        val levelField = fields { levelUpAt(it) != null }.firstOrNull()
        // Evolutions: the first stage of each starter evolves into the next species.
        var evoField: Int? = null; var evoSize = 8
        for (size in listOf(8, 12, 16)) {
            evoField = offsets.firstOrNull { o ->
                listOf(1, 4, 7).all { s -> field(s, o)?.let { evolutionsAt(it, size) }?.firstOrNull() == s + 1 } &&
                    field(133, o)?.let { evolutionsAt(it, size) }?.let { it.size >= 3 } == true
            }
            if (evoField != null) { evoSize = size; break }
        }
        // Teachable moves (TMs and tutors): plain move lists, longer than egg-move lists.
        val listFields = fields { p -> moveListAt(p)?.let { it.size >= 4 } == true && levelUpAt(p) == null } - setOfNotNull(levelField, evoField)
        val teachField = listFields.maxByOrNull { o -> samples.sumOf { s -> field(s, o)?.let(::moveListAt)?.size ?: 0 } }

        val levelUp = levelField?.let { o -> List(speciesCount) { s -> field(s, o)?.let(::levelUpAt).orEmpty() } }.orEmpty()
        val evolutions = evoField?.let { o -> List(speciesCount) { s -> field(s, o)?.let { evolutionsAt(it, evoSize) }.orEmpty() } }.orEmpty()
        val teach = teachField?.let { o -> List(speciesCount) { s -> field(s, o)?.let(::moveListAt).orEmpty() } }
            ?: teachableTable(referenced)
        return Parts(levelUp, evolutions, teach)
    }

    /**
     * Older versions keep teachable moves in their own table: one pointer per species
     * to a move list. Evolution families share most of theirs.
     */
    private fun teachableTable(referenced: BitSet): List<List<Int>>? {
        fun similar(a: List<Int>, b: List<Int>): Boolean {
            val union = (a + b).toSet().size
            return union > 0 && a.toSet().intersect(b.toSet()).size * 2 >= union
        }
        var t = referenced.nextSetBit(0)
        while (t >= 0) {
            if (t % 4 == 0 && t + 4 * speciesCount <= rom.size) {
                fun list(s: Int) = ptr(t + 4 * s)?.takeIf { levelUpAt(it) == null }?.let(::moveListAt)
                val l1 = list(1); val l2 = list(2)
                if (l1 != null && l2 != null && l1.size >= 8 && similar(l1, l2) &&
                    list(4)?.let { a -> list(5)?.let { b -> similar(a, b) } } == true &&
                    list(7)?.let { a -> list(8)?.let { b -> similar(a, b) } } == true
                ) {
                    val lists = List(speciesCount) { s -> list(s).orEmpty() }
                    if (lists.count { it.isNotEmpty() } * 2 >= speciesCount) return lists
                }
            }
            t = referenced.nextSetBit(t + 1)
        }
        return null
    }
}
