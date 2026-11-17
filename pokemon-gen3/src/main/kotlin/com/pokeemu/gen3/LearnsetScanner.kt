package com.pokeemu.gen3

import com.pokeemu.core.Learnsets
import java.util.BitSet

/**
 * Finds which moves each species can learn in a GBA Pokémon ROM: level-up
 * learnsets, TM/HM compatibility, egg moves and evolutions.
 *
 * Hacks move these tables (and expanded engines change their format), often
 * leaving the old ones behind. So only tables the game's code points to are
 * considered; each candidate is then checked against the shape the table must
 * have, plus a few facts every game keeps (Bulbasaur evolves into Ivysaur...).
 */
internal class LearnsetScanner(
    private val rom: ByteArray,
    /** Offsets some ROM pointer points to (see RomScanner). */
    private val referenced: BitSet,
    private val speciesCount: Int,
    private val moveCount: Int,
    private val itemNames: List<String>,
) {
    private fun targets(align: Int): Sequence<Int> = sequence {
        var t = referenced.nextSetBit(0)
        while (t >= 0) {
            if (t % align == 0) yield(t)
            t = referenced.nextSetBit(t + 1)
        }
    }

    private fun romOffset(pointer: Long): Int? = (pointer - 0x08000000L).takeIf { it in 0 until rom.size }?.toInt()
    private fun u16At(o: Int) = if (o >= 0 && o + 2 <= rom.size) rom.u16(o) else null

    fun scan(report: MutableList<String>): Learnsets {
        val levelUp = levelUp()
        report += levelUp?.let { "Found level-up moves: ${it.second}, ${it.first.size} species" } ?: "Could not find level-up moves"
        val evolutions = evolutions()
        report += evolutions?.let { "Found evolutions: ${it.second}" } ?: "Could not find evolutions"
        val egg = eggMoves()
        report += egg?.let { "Found egg moves for ${it.size} species" } ?: "Could not find egg moves"
        val tm = tmData()
        report += tm?.let { "Found TM/HM moves: ${it.first.size - it.second} TMs, ${it.second} HMs" } ?: "Could not find TM/HM compatibility"
        report += "Move tutors aren't read from GBA ROMs yet"
        return Learnsets(
            levelUp = levelUp?.first.orEmpty(),
            tmMoves = tm?.first.orEmpty(), hmCount = tm?.second ?: 0, tmCompat = tm?.third.orEmpty(),
            eggMoves = egg.orEmpty(),
            evolutions = evolutions?.first.orEmpty(),
        )
    }

    // ---- level-up ---------------------------------------------------------------------

    /** Vanilla: u16 (move | level << 9), ending with 0xFFFF. */
    private fun learnsetPacked(o: Int): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until 64) {
            val v = u16At(o + 2 * i) ?: return null
            if (v == 0xFFFF) return out
            val move = v and 0x1FF
            val level = v shr 9
            if (move == 0 || move >= moveCount || level > 100) return null
            out += level to move
        }
        return null
    }

    /** Expanded engines: { u16 move; u8 level; u8 pad }, ending with move 0xFFFF or (0, 0xFF). */
    private fun learnsetWide(o: Int): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until 128) {
            val move = u16At(o + 4 * i) ?: return null
            val level = rom.getOrNull(o + 4 * i + 2)?.toInt()?.and(0xFF) ?: return null
            if (move == 0xFFFF || (move == 0 && level == 0xFF)) return out
            if (move == 0 || move >= moveCount || level > 101) return null
            out += level to move
        }
        return null
    }

    /** Some expanded engines: packed { u16 move; u8 level }, ending with (0, 0xFF) or move 0xFFFF. */
    private fun learnset3(o: Int): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until 128) {
            val move = u16At(o + 3 * i) ?: return null
            val level = rom.getOrNull(o + 3 * i + 2)?.toInt()?.and(0xFF) ?: return null
            if (move == 0xFFFF || (move == 0 && level == 0xFF)) return out
            if (move == 0 || move >= moveCount || level > 101) return null
            out += level to move
        }
        return null
    }

    private val formats: List<Pair<String, (Int) -> List<Pair<Int, Int>>?>> = listOf(
        "vanilla format" to ::learnsetPacked,
        "expanded format" to ::learnsetWide,
        "expanded 3-byte format" to ::learnset3,
    )

    private fun levelUp(): Pair<List<List<Pair<Int, Int>>>, String>? {
        val minimum = (speciesCount * 4 / 5).coerceIn(100, 380)
        var best: Triple<Int, String, Int>? = null // table, format, count
        for (t in targets(4)) {
            if (t + 8 > rom.size) continue
            val first = romOffset(rom.u32(t + 4)) ?: continue
            for ((format, parse) in formats) {
                if (parse(first).isNullOrEmpty()) continue // Bulbasaur learns something
                var n = 1
                var nonEmpty = 1
                while (n < speciesCount + 64) {
                    val at = t + 4 * (n + 1)
                    if (at + 4 > rom.size) break
                    val p = romOffset(rom.u32(at)) ?: break
                    val list = parse(p) ?: break
                    if (list.isNotEmpty()) nonEmpty++ // placeholder species may learn nothing
                    n++
                }
                if (n >= minimum && nonEmpty * 5 >= n * 4 && (best == null || n > best.third)) best = Triple(t, format, n)
            }
        }
        val (t, format, n) = best ?: return null
        val parse = formats.first { it.first == format }.second
        val list = List(minOf(n + 1, speciesCount)) { s -> romOffset(rom.u32(t + 4 * s))?.let(parse).orEmpty() }
        return list to "$format at 0x%06X".format(t)
    }

    // ---- evolutions -------------------------------------------------------------------

    private fun evolutions(): Pair<List<List<Int>>, String>? {
        fun target(t: Int, stride: Int, species: Int, slot: Int = 0) = u16At(t + stride * species + 8 * slot + 4)
        for (t in targets(4)) {
            for (stride in EVO_STRIDES) {
                // First-generation starters, Caterpie and Pidgey lines: kept by every game and hack.
                if (EVO_ANCHORS.any { (from, to) -> target(t, stride, from) != to }) continue
                if (t + stride * speciesCount > rom.size) continue
                val list = List(speciesCount) { s ->
                    (0 until stride / 8).mapNotNull { slot ->
                        val method = u16At(t + stride * s + 8 * slot) ?: 0
                        val to = target(t, stride, s, slot) ?: 0
                        to.takeIf { method != 0 && it in 1 until speciesCount }
                    }
                }
                return list to "${stride / 8} per species at 0x%06X".format(t)
            }
        }
        return null
    }

    // ---- egg moves --------------------------------------------------------------------

    private fun eggMoves(): Map<Int, List<Int>>? {
        var best: Map<Int, List<Int>>? = null
        for (t in targets(2)) {
            val first = u16At(t) ?: continue
            if (first <= Learnsets.EGG_MARKER || first - Learnsets.EGG_MARKER >= speciesCount) continue
            val parsed = Learnsets.parseEggMoves({ i -> u16At(t + 2 * i) }, speciesCount, moveCount) ?: continue
            if (best == null || parsed.size > best.size) best = parsed
        }
        return best
    }

    // ---- TMs and HMs ------------------------------------------------------------------

    /** (TM moves then HM moves, HM count, per-species compatibility). */
    private fun tmData(): Triple<List<Int>, Int, List<BitSet>>? {
        val hmCount = itemNames.count { HM_NAME.matches(it.trim()) }.takeIf { it in 1..8 } ?: 8
        val namedTms = itemNames.count { TM_NAME.matches(it.trim()) }
        // Vanilla: 8 bytes per species (50 TMs + 8 HMs); expanded engines: 16 bytes (up to 120 TMs + 8 HMs).
        for (stride in listOf(8, 16)) {
            val compat = tmCompat(stride) ?: continue
            val bitsUsed = compat.maxOf { it.length() }
            val counts = (listOf(namedTms) + TM_COUNTS).filter { it > 0 && it + hmCount >= bitsUsed && it + hmCount <= stride * 8 }.distinct()
            for (tmCount in counts) {
                val moves = tmMoveList(tmCount, hmCount) ?: continue
                return Triple(moves, hmCount, compat)
            }
        }
        return null
    }

    /** The TM/HM move list: [tmCount] moves right before the usual HM moves. */
    private fun tmMoveList(tmCount: Int, hmCount: Int): List<Int>? {
        val hms = VANILLA_HMS.take(hmCount)
        val pattern = ByteArray(hms.size * 2).also { b -> hms.forEachIndexed { i, m -> b.put16(2 * i, m) } }
        var at = indexOf(pattern, 0)
        while (at >= 0) {
            val start = at - 2 * tmCount
            if (at % 2 == 0 && start >= 0) {
                val tms = List(tmCount) { rom.u16(start + 2 * it) }
                if (tms.all { it in 1 until moveCount } && tms.toSet().size >= tmCount * 9 / 10) return tms + hms
            }
            at = indexOf(pattern, at + 1)
        }
        return null
    }

    private fun tmCompat(stride: Int): List<BitSet>? {
        val bits = stride * 8
        fun entry(t: Int, s: Int) = BitSet.valueOf(rom.copyOfRange(t + stride * s, t + stride * (s + 1)))
        fun similar(a: BitSet, b: BitSet): Boolean {
            val union = (a.clone() as BitSet).apply { or(b) }.cardinality()
            val both = (a.clone() as BitSet).apply { and(b) }.cardinality()
            return union > 0 && both * 2 >= union
        }
        for (t in targets(4)) {
            if (t + stride * speciesCount > rom.size) continue
            if ((0 until stride).any { rom[t + it].toInt() != 0 }) continue // species 0 learns nothing
            val one = entry(t, 1)
            if (one.isEmpty || one.length() > bits) continue
            // Evolution families share most TMs.
            if (!similar(one, entry(t, 2)) || !similar(entry(t, 4), entry(t, 5)) || !similar(entry(t, 7), entry(t, 8))) continue
            val list = List(speciesCount) { s -> entry(t, s) }
            val plausible = list.drop(1).count { !it.isEmpty && it.length() <= bits }
            if (plausible * 10 < (speciesCount - 1) * 8) continue
            return list
        }
        return null
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        var i = from
        val end = rom.size - pattern.size
        outer@ while (i <= end) {
            if (rom[i] == pattern[0]) {
                for (k in 1 until pattern.size) if (rom[i + k] != pattern[k]) { i++; continue@outer }
                return i
            }
            i++
        }
        return -1
    }

    private companion object {
        /** Bytes per species in evolution tables: 5 x 8 (vanilla) up to 16 x 8 and more (expanded engines). */
        val EVO_STRIDES = listOf(40, 48, 64, 80, 128, 160, 256)
        val EVO_ANCHORS = listOf(1 to 2, 2 to 3, 4 to 5, 5 to 6, 7 to 8, 8 to 9, 10 to 11, 11 to 12, 16 to 17, 17 to 18)
        /** Cut, Fly, Surf, Strength, Flash, Rock Smash, Waterfall, Dive. */
        val VANILLA_HMS = listOf(15, 19, 57, 70, 148, 249, 127, 291)
        /** TM counts used by vanilla games and common expansions, most specific first. */
        val TM_COUNTS = listOf(50, 58, 92, 95, 100, 110, 112, 120)
        val TM_NAME = Regex("""TM\s*\d{1,3}""", RegexOption.IGNORE_CASE)
        val HM_NAME = Regex("""HM\s*\d{1,2}""", RegexOption.IGNORE_CASE)
    }
}
