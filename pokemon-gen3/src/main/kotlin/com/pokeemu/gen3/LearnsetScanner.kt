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
    /** The item table (base, entry size), when its names are stored inline in the entries. */
    private val itemTable: Pair<Int, Int>? = null,
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
        val (t, stride) = Gen3Evolutions.locate(rom, referenced, speciesCount) ?: return null
        val list = List(speciesCount) { s ->
            (0 until stride / 8).mapNotNull { slot ->
                val method = u16At(t + stride * s + 8 * slot) ?: 0
                val to = u16At(t + stride * s + 8 * slot + 4) ?: 0
                to.takeIf { method != 0 && it in 1 until speciesCount }
            }
        }
        return list to "${stride / 8} per species at 0x%06X".format(t)
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
        // Preferred: the TM items themselves say which move they teach (works however a hack numbers TMs).
        tmListFromItems()?.let { (moves, hms) ->
            val stride = if (moves.size <= 64) 8 else 16
            tmCompat(stride, bits = moves.size)?.let { return Triple(moves, hms, it) }
        }
        return tmDataByHms()
    }

    /**
     * The TM/HM move list, found through the TM items: each TM item's
     * description is its move's description (the same text pointer), which
     * gives the move for most TMs; the game's TM move list is then the u16
     * array that agrees with all of them, and fills in the rest.
     * Returns (TM moves then HM moves, HM count).
     */
    internal fun tmListFromItems(): Pair<List<Int>, Int>? {
        val (base, stride) = itemTable ?: return null
        if (stride < ITEM_SIZE) return null
        // TM and HM items.
        val tmItems = ArrayList<Pair<MatchResult, Int>>()
        for (i in 0 until 2048) {
            val o = base + i * stride
            if (o + stride > rom.size) break
            val len = (0 until 14).firstOrNull { rom.u8(o + it) == Gen3Text.TERMINATOR } ?: continue
            val name = Gen3Text.decode(rom, o, len).trim()
            val m = TM_ITEM.matchEntire(name) ?: continue
            tmItems += m to o
        }
        if (tmItems.size < 8) return null
        // Their description pointer: +20 in the vanilla 44-byte item; larger items move it (R.O.W.E.: +28).
        fun pointsToText(p: Long) = romOffset(p)?.let { t -> (0 until 4).all { k -> t + k < rom.size && (Gen3Text.isChar(rom.u8(t + k)) || rom.u8(t + k) == 0xFE) } } == true
        val descAt = (listOf(20) + (16..stride - 4 step 4)).firstOrNull { d -> tmItems.take(8).all { (_, o) -> pointsToText(rom.u32(o + d)) } } ?: return null
        val tms = java.util.TreeMap<Int, Long>()
        val hms = java.util.TreeMap<Int, Long>()
        for ((m, o) in tmItems) {
            val number = m.groupValues[2].toInt()
            val desc = rom.u32(o + descAt)
            if (m.groupValues[1].equals("TM", true)) tms.putIfAbsent(number, desc) else hms.putIfAbsent(number, desc)
        }
        if (tms.isEmpty()) return null
        val tmCount = tms.lastKey()
        val hmCount = if (hms.isEmpty()) 0 else hms.lastKey()
        fun index(hm: Boolean, n: Int) = if (hm) tmCount + n - 1 else n - 1
        val size = tmCount + hmCount

        // Where each description pointer appears (outside the item table): one pass over the ROM.
        val wanted = (tms.values + hms.values).toHashSet()
        val where = HashMap<Long, MutableList<Int>>()
        val itemEnd = base + 2048 * stride
        var p = 0
        while (p + 4 <= rom.size) {
            val v = rom.u32(p)
            if (v in wanted && (p < base || p >= itemEnd)) where.getOrPut(v) { mutableListOf() } += p
            p += 4
        }
        // Move description tables, anchored on HMs every game keeps (Cut, Fly, Surf, Strength).
        val anchors = listOf(1 to 15, 2 to 19, 3 to 57, 4 to 70)
        val tables = anchors.flatMap { (hm, move) -> where[hms[hm]].orEmpty().map { it - 4 * (move - 1) } }.distinct()
            .ifEmpty { descriptionRuns(where.values.flatten()) } // no HMs (Radical Red): find the table by its shape
        fun resolve(table: Int): Map<Int, Int> = buildMap {
            for ((n, d) in tms) where[d]?.firstOrNull { it >= table && it < table + 4 * moveCount }?.let { put(index(false, n), (it - table) / 4 + 1) }
            for ((n, d) in hms) where[d]?.firstOrNull { it >= table && it < table + 4 * moveCount }?.let { put(index(true, n), (it - table) / 4 + 1) }
        }
        // Hacks keep the old table next to their own: combine the two that resolve the most.
        val known = HashMap<Int, Int>()
        tables.map(::resolve).sortedByDescending { it.size }.take(2).forEach { r -> r.forEach { (k, v) -> known.putIfAbsent(k, v) } }
        if (known.size < size / 3) return null

        // The game's own list: a u16 array agreeing with (nearly) every resolved TM. Some hacks
        // give a TM item an old move's description (Radical Red), so a few may disagree.
        var best: List<Int>? = null
        var bestScore = known.size * 9 / 10 - 1
        for ((key, value) in known.entries.sortedBy { it.key }.take(3)) {
            var at = 0
            while (at + 2 <= rom.size) {
                if (rom.u16(at) == value) {
                    val start = at - 2 * key
                    if (start >= 0 && start + 2 * size <= rom.size) {
                        val score = known.count { (i, m) -> rom.u16(start + 2 * i) == m }
                        if (score > bestScore) {
                            val list = List(size) { rom.u16(start + 2 * it) }
                            if (list.all { it in 1 until moveCount }) { best = list; bestScore = score }
                        }
                    }
                }
                at += 2
            }
            if (best != null) return best to hmCount
        }
        // No such array: usable only if every TM was resolved.
        return if (known.size == size) List(size) { known.getValue(it) } to hmCount else null
    }

    /**
     * Move description tables around [hits] (places a TM's description pointer appears):
     * runs of text pointers about as long as the move list. Returns where move 1's entry is.
     */
    private fun descriptionRuns(hits: List<Int>): List<Int> {
        // A description: readable text (letters, spaces, line breaks) ending in a terminator.
        fun isText(at: Int): Boolean {
            var n = 0
            while (n < 200 && at + n < rom.size) {
                val b = rom.u8(at + n)
                if (b == Gen3Text.TERMINATOR) return n >= 3
                if (!Gen3Text.isChar(b) && b != 0xFE) return false
                n++
            }
            return false
        }
        fun isPointer(o: Int) = o >= 0 && o + 4 <= rom.size && romOffset(rom.u32(o))?.let(::isText) == true
        val found = HashMap<Int, Int>() // run start -> TM descriptions inside
        for (q in hits) {
            if (q % 4 != 0) continue
            var s = q
            while (isPointer(s - 4) && q - s < 4 * (moveCount + 64)) s -= 4
            var e = q
            while (isPointer(e + 4) && e - s < 4 * (moveCount + 64)) e += 4
            val length = (e - s) / 4 + 1
            if (length * 10 >= (moveCount - 1) * 7 && length <= moveCount + 64) found.merge(s, 1, Int::plus)
        }
        return found.entries.sortedByDescending { it.value }.map { it.key }.take(2)
    }

    private fun tmDataByHms(): Triple<List<Int>, Int, List<BitSet>>? {
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

    /** The TM compatibility table, [stride] bytes per species, using at most [bits] bits. */
    internal fun tmCompat(stride: Int, bits: Int = stride * 8): List<BitSet>? {
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
        const val ITEM_SIZE = 44
        val TM_ITEM = Regex("""(TM|HM)\s*0*(\d{1,3})""", RegexOption.IGNORE_CASE)
        val TM_NAME = Regex("""TM\s*\d{1,3}""", RegexOption.IGNORE_CASE)
        val HM_NAME = Regex("""HM\s*\d{1,2}""", RegexOption.IGNORE_CASE)
    }
}
