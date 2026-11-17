package com.pokeemu.gen4

import com.pokeemu.core.Learnsets
import java.util.BitSet

/**
 * Reads which moves each species can learn from a DS Pokémon ROM: level-up
 * learnsets, evolutions and egg moves from their archives (by content, so
 * hacks that grow them still work), TM/HM compatibility from the personal
 * data, and the TM move list from the game code.
 */
internal object Gen4Learnsets {
    /** HGSS, then Diamond/Pearl/Platinum. */
    private val LEARNSET_ARCHIVES = listOf("a/0/3/3", "poketool/personal/wotbl.narc", "poketool/personal_pearl/wotbl.narc")
    private val EVOLUTION_ARCHIVES = listOf("a/0/3/4", "poketool/personal/evo.narc", "poketool/personal_pearl/evo.narc")
    private val EGG_ARCHIVES = listOf("a/2/2/9")
    /** Engines that drop the TM bits from the personal data keep, per species, every move it can be taught. */
    private val TEACHABLE_ARCHIVES = listOf("data/tutor_moves.narc")

    /** HM moves at the end of the TM list. DP/Pt: ... Defog ...; HGSS: ... Whirlpool ... */
    private val HM_LISTS = listOf(
        listOf(15, 19, 57, 70, 250, 249, 127, 431),
        listOf(15, 19, 57, 70, 432, 249, 127, 431),
    )
    private const val TM_COUNT = 92

    fun scan(
        fs: NdsFileSystem,
        rom: RomSource,
        personal: Narc?,
        speciesCount: Int,
        moveCount: Int,
        report: MutableList<String>,
    ): Learnsets? {
        val levelUp = levelUp(fs, moveCount)
        report += levelUp?.let { "Level-up moves for ${it.size} species" } ?: "Level-up moves not found"
        val evolutions = evolutions(fs, speciesCount)
        report += if (evolutions != null) "Evolutions found" else "Evolutions not found"
        val egg = eggMoves(fs, speciesCount, moveCount)
        report += egg?.let { "Egg moves for ${it.size} species" } ?: "Egg moves not found"
        val tmMoves = tmMoveList(rom, moveCount)
        val compat = personal?.let { p -> List(p.size) { i -> p[i].let { e -> if (e.size >= 0x2C) BitSet.valueOf(e.copyOfRange(0x1C, 0x2C)) else BitSet() } } }
            ?.takeIf(::plausibleTmCompat)
        val hasTm = tmMoves != null && compat != null
        val teachable = if (hasTm) null else teachable(fs, moveCount)
        report += when {
            hasTm -> "TM/HM compatibility found"
            teachable != null -> "TM, HM and tutor moves found (one list per species)"
            else -> "TM/HM compatibility not found"
        }
        if (teachable == null) report += "Move tutors aren't read from DS ROMs yet"
        val l = Learnsets(
            levelUp = levelUp.orEmpty(),
            tmMoves = if (hasTm) tmMoves!! else emptyList(), hmCount = if (hasTm) 8 else 0,
            tmCompat = if (hasTm) compat!! else emptyList(),
            eggMoves = egg.orEmpty(),
            tutorMoves = teachable?.first.orEmpty(), tutorCompat = teachable?.second.orEmpty(),
            evolutions = evolutions.orEmpty(),
            tutorIncludesTms = teachable != null,
        )
        return l.takeIf { it.has.isNotEmpty() }
    }

    private fun narc(fs: NdsFileSystem, path: String): Narc? =
        runCatching { fs.read(path)?.takeIf(Narc::isNarc)?.let(Narc::parse) }.getOrNull()

    /**
     * Level-up entry layouts: vanilla u16 (move | level << 9); expanded-move
     * hacks use two u16s, move then level or level then move. All end with 0xFFFF.
     */
    private enum class Layout { PACKED, MOVE_LEVEL, LEVEL_MOVE }

    private fun parseLevelUp(b: ByteArray, layout: Layout, moveCount: Int): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>()
        val step = if (layout == Layout.PACKED) 2 else 4
        var i = 0
        while (i + 2 <= b.size) {
            val v = b.u16(i)
            if (v == 0xFFFF) return out
            if (layout != Layout.PACKED && i + 4 > b.size) return null
            val (move, level) = when (layout) {
                Layout.PACKED -> (v and 0x1FF) to (v shr 9)
                Layout.MOVE_LEVEL -> v to b.u16(i + 2)
                Layout.LEVEL_MOVE -> b.u16(i + 2) to v
            }
            if (move == 0 || move >= moveCount || level > 100) return null
            out += level to move
            i += step
        }
        return null
    }

    /** A real learnset: levels never go down, and it isn't all "level 0" (a misread layout). */
    private fun plausible(list: List<Pair<Int, Int>>) =
        list.zipWithNext().all { (a, b) -> b.first >= a.first } && list.any { it.first > 1 }

    private fun levelUp(fs: NdsFileSystem, moveCount: Int): List<List<Pair<Int, Int>>>? {
        for (path in LEARNSET_ARCHIVES) {
            val narc = narc(fs, path) ?: continue
            if (narc.size < 400) continue
            // Several layouts can parse the same bytes; keep the one giving the most plausible learnsets.
            val best = Layout.entries.map { layout ->
                val lists = List(narc.size) { parseLevelUp(narc[it], layout, moveCount) }
                layout to lists
            }.maxByOrNull { (_, lists) -> lists.count { it != null && plausible(it) } } ?: continue
            val lists = best.second
            if (lists.count { it != null && plausible(it) } * 10 < narc.size * 6) continue
            return lists.map { it.orEmpty() }
        }
        return null
    }

    private fun evolutions(fs: NdsFileSystem, speciesCount: Int): List<List<Int>>? {
        for (path in EVOLUTION_ARCHIVES) {
            val narc = narc(fs, path) ?: continue
            if (narc.size < 400) continue
            fun targets(i: Int): List<Int> {
                val b = narc[i]
                return (0 until b.size / 6).mapNotNull { k ->
                    val method = b.u16(6 * k)
                    val to = b.u16(6 * k + 4)
                    to.takeIf { method != 0 && it in 1 until speciesCount.coerceAtLeast(narc.size) }
                }
            }
            if (targets(1).firstOrNull() != 2 || targets(2).firstOrNull() != 3 || targets(4).firstOrNull() != 5) continue
            return List(narc.size, ::targets)
        }
        return null
    }

    private fun eggMoves(fs: NdsFileSystem, speciesCount: Int, moveCount: Int): Map<Int, List<Int>>? {
        for (path in EGG_ARCHIVES) {
            val narc = narc(fs, path) ?: continue
            if (narc.size >= 400) {
                // One file per species: its egg moves, ending with 0xFFFF.
                val map = HashMap<Int, List<Int>>()
                for (s in 0 until narc.size) {
                    val b = narc[s]
                    val moves = (0 until b.size / 2).map { b.u16(2 * it) }.takeWhile { it != 0xFFFF }
                    if (moves.any { it == 0 || it >= moveCount }) continue
                    if (moves.isNotEmpty()) map[s] = moves
                }
                if (map.size >= 50) return map
                continue
            }
            for (i in 0 until narc.size) {
                val b = narc[i]
                Learnsets.parseEggMoves({ k -> if (2 * k + 2 <= b.size) b.u16(2 * k) else null }, speciesCount, moveCount)?.let { return it }
            }
        }
        return null
    }

    /**
     * Per-species lists of every move a species can be taught (u16 moves,
     * ending at 0xFFFF or 0). Returns (all such moves, per-species bits over them).
     */
    private fun teachable(fs: NdsFileSystem, moveCount: Int): Pair<List<Int>, List<BitSet>>? {
        for (path in TEACHABLE_ARCHIVES) {
            val narc = narc(fs, path) ?: continue
            if (narc.size < 400) continue
            val lists = List(narc.size) { s ->
                val b = narc[s]
                (0 until b.size / 2).map { b.u16(2 * it) }.takeWhile { it != 0xFFFF && it != 0 }.filter { it < moveCount }
            }
            if (lists.count { it.isNotEmpty() } * 2 < narc.size) continue
            val moves = lists.flatten().distinct().sorted()
            val index = moves.withIndex().associate { (i, m) -> m to i }
            return moves to lists.map { l -> BitSet().apply { l.forEach { set(index.getValue(it)) } } }
        }
        return null
    }

    /**
     * Some hacks keep TM compatibility elsewhere and leave stale bytes in the
     * personal data: evolution families must share most TMs, and most species learn some.
     */
    private fun plausibleTmCompat(c: List<BitSet>): Boolean {
        if (c.size < 10) return false
        fun similar(a: Int, b: Int): Boolean {
            val union = (c[a].clone() as BitSet).apply { or(c[b]) }.cardinality()
            val both = (c[a].clone() as BitSet).apply { and(c[b]) }.cardinality()
            return union > 0 && both * 2 >= union
        }
        return similar(1, 2) && similar(4, 5) && similar(7, 8) && c.drop(1).count { !it.isEmpty } * 10 >= (c.size - 1) * 7
    }

    /** The 92 TMs then 8 HMs, found in the ARM9 code by the HM moves at the end. */
    private fun tmMoveList(rom: RomSource, moveCount: Int): List<Int>? {
        val arm9 = Arm9.read(rom)
        for (hms in HM_LISTS) {
            val pattern = ByteArray(16).also { b -> hms.forEachIndexed { i, m -> b[2 * i] = m.toByte(); b[2 * i + 1] = (m shr 8).toByte() } }
            var at = 0
            while (at <= arm9.size - 16) {
                if (arm9[at] == pattern[0] && (0 until 16).all { arm9[at + it] == pattern[it] }) {
                    val start = at - 2 * TM_COUNT
                    if (start >= 0) {
                        val tms = List(TM_COUNT) { arm9.u16(start + 2 * it) }
                        if (tms.all { it in 1 until moveCount }) return tms + hms
                    }
                }
                at += 2
            }
        }
        return null
    }
}
