package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender

/**
 * Finds game data tables in a Gen 3 ROM by content, not by fixed address, so
 * it works for official games and for hacks that move or enlarge tables
 * (decomp/expansion builds, CFRU binary hacks).
 *
 * A name table is found from three consecutive known names (e.g. BULBASAUR,
 * IVYSAUR, VENUSAUR): wherever they sit at equal spacing, that spacing is the
 * entry size. This covers plain name arrays (11 bytes/entry in vanilla),
 * names embedded in larger structs, and arrays of pointers to names.
 */
class RomScanner(private val rom: ByteArray) {

    private val report = mutableListOf<String>()

    /**
     * Offsets some 4-byte ROM pointer points to. Hacks often move a table and
     * leave the old copy behind; the copy in use is the one the code points to.
     */
    private val referenced: java.util.BitSet by lazy {
        val set = java.util.BitSet(rom.size)
        var i = 0
        while (i + 4 <= rom.size) {
            val b3 = rom[i + 3].toInt() and 0xFF
            if (b3 == 0x08 || b3 == 0x09) {
                val target = rom.u32(i) - 0x08000000L
                if (target in 0 until rom.size) set.set(target.toInt())
            }
            i += 4
        }
        set
    }

    fun scan(): RomData {
        val tables = listOf(
            names("species names", listOf("BULBASAUR", "IVYSAUR", "VENUSAUR"), firstIndex = 1, maxLen = 12, max = 2048),
            names("move names", listOf("POUND", "KARATE CHOP", "DOUBLESLAP"), firstIndex = 1, maxLen = 16, max = 1024,
                altAnchors = listOf("POUND", "KARATE CHOP", "DOUBLE SLAP")),
            names("ability names", listOf("STENCH", "DRIZZLE", "SPEED BOOST"), firstIndex = 1, maxLen = 16, max = 512),
            names("item names", listOf("MASTER BALL", "ULTRA BALL", "GREAT BALL"), firstIndex = 1, maxLen = 20, max = 2048),
        ).map(::trimAtNextTable)
        tables.forEach { report += it.description() }
        val (species, moves, abilities, items) = tables.map { it.names }
        val base = baseStats(species.size.coerceAtLeast(412))
        val moveData = moveData(moves.size.coerceAtLeast(355))
        val pp = moveData?.pp.orEmpty()
        val learnsets = LearnsetScanner(
            rom, referenced,
            speciesCount = species.size.takeIf { it > 0 } ?: 412,
            moveCount = moves.size.takeIf { it > 0 } ?: 355,
            itemNames = items,
        ).scan(report).takeIf { it.has.isNotEmpty() }
        val sprites = Gen3Sprites.scan(rom, referenced, species.size.takeIf { it > 0 } ?: 412, report)
        return RomData(
            species, moves, abilities, items, base, pp, report.toList(), learnsets,
            moveData?.power.orEmpty(), moveData?.type.orEmpty(), moveData?.accuracy.orEmpty(), moveData?.category.orEmpty(),
            sprites,
        )
    }

    private class NameTable(val what: String, val table: Table?, val names: List<String>) {
        fun description() = if (table == null) "Could not find $what — showing numbers instead"
        else "Found $what: ${names.size} entries (${if (table.pointers) "pointer" else "inline"} table, " +
            "entry size ${table.stride}, at 0x%06X)".format(table.base)
    }

    private val foundTables = mutableListOf<Table>()

    /**
     * Inline tables are often packed back to back (vanilla move names start right
     * after species names), and the next table's text also looks like valid
     * entries. End each inline table where another found table begins.
     */
    private fun trimAtNextTable(t: NameTable): NameTable {
        val table = t.table ?: return t
        if (table.pointers) return t
        val end = table.base + t.names.size * table.stride
        val next = foundTables.map { it.base }.filter { it > table.base && it < end }.minOrNull() ?: return t
        return NameTable(t.what, table, t.names.take((next - table.base) / table.stride))
    }

    // ---- name tables ------------------------------------------------------------------

    private data class Table(val base: Int, val stride: Int, val pointers: Boolean, val firstIndex: Int)

    private fun names(
        what: String, anchors: List<String>, firstIndex: Int, maxLen: Int, max: Int,
        altAnchors: List<String>? = null,
    ): NameTable {
        val variants = listOfNotNull(anchors, altAnchors).flatMap { listOf(it, it.map(::titleCase)) }.distinct()
        val candidates = variants.flatMap { v -> findInline(v, firstIndex) + findPointers(v, firstIndex) }.distinct()
            .map { it to readNames(it, maxLen, max) }
            .filter { (_, names) -> names.size > firstIndex + 2 }
        // The table the game uses is the one its code points to; otherwise the biggest.
        val (table, names) = candidates.maxWithOrNull(compareBy({ referenced[it.first.base] }, { it.second.size }))
            ?: return NameTable(what, null, emptyList())
        foundTables += table
        return NameTable(what, table, names)
    }

    private fun titleCase(s: String) = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Encoded string plus its terminator. */
    private fun enc(s: String): ByteArray = Gen3Text.encode(s, s.length + 1)

    private fun findInline(anchors: List<String>, firstIndex: Int): List<Table> {
        val a = anchors.map(::enc)
        val out = mutableListOf<Table>()
        for (p0 in occurrences(a[0])) {
            for (stride in a[0].size..MAX_STRIDE) {
                if (matches(a[1], p0 + stride) && matches(a[2], p0 + 2 * stride)) {
                    val base = p0 - firstIndex * stride
                    if (base >= 0) out += Table(base, stride, pointers = false, firstIndex)
                    break
                }
            }
        }
        return out
    }

    private fun findPointers(anchors: List<String>, firstIndex: Int): List<Table> {
        val strings = anchors.map { s -> occurrences(enc(s)) }
        if (strings.any { it.isEmpty() }) return emptyList()
        val out = mutableListOf<Table>()
        val targets1 = strings[1].map { ptr(it) }.toSet()
        val targets2 = strings[2].map { ptr(it) }.toSet()
        for (s0 in strings[0]) {
            for (q0 in occurrences(le32(ptr(s0)))) {
                if (q0 % 4 != 0) continue
                for (stride in 4..MAX_STRIDE step 4) {
                    val q1 = q0 + stride
                    val q2 = q0 + 2 * stride
                    if (q2 + 4 > rom.size) break
                    if (rom.u32(q1) in targets1 && rom.u32(q2) in targets2) {
                        val base = q0 - firstIndex * stride
                        if (base >= 0) out += Table(base, stride, pointers = true, firstIndex)
                        break
                    }
                }
            }
        }
        return out
    }

    private fun readNames(t: Table, maxLen: Int, max: Int): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until max) {
            val slot = t.base + i * t.stride
            if (slot + 4 > rom.size) break
            val at = if (t.pointers) romOffset(rom.u32(slot)) else slot
            val name = at?.let { readText(it, maxLen) }
            if (name == null) {
                if (i < t.firstIndex) { out += ""; continue } // placeholder entry 0 may be anything
                break
            }
            out += name
        }
        return out.dropLastWhile { it.isBlank() }
    }

    /** A plausible terminated string at [at], or null. */
    private fun readText(at: Int, maxLen: Int): String? {
        var known = 0
        var len = 0
        while (len <= maxLen) {
            if (at + len >= rom.size) return null
            val b = rom.u8(at + len)
            if (b == Gen3Text.TERMINATOR) break
            if (b in 0xF7..0xFE) return null // control codes don't appear in names
            if (Gen3Text.isChar(b)) known++
            len++
        }
        if (len > maxLen) return null
        if (len > 0 && known * 4 < len * 3) return null // mostly unknown bytes: not text
        return Gen3Text.decode(rom, at, len)
    }

    // ---- base stats -------------------------------------------------------------------

    /**
     * Facts about early species (same index in every Gen 3 engine) used to
     * find and decode the base-stats table. Hacks often rebalance Pokémon, so
     * apart from the Bulbasaur line (the search anchor) nothing has to match
     * exactly: checks and field offsets go by majority.
     */
    private data class Fact(
        val species: Int, val stats: List<Int>, val t1: Int, val t2: Int, val gender: Int, val growth: Int, val ability: Int,
    )

    private val facts = listOf(
        Fact(1, listOf(45, 49, 49, 45, 65, 65), 12, 3, 31, 3, 65), // Bulbasaur: Grass/Poison, Overgrow
        Fact(2, listOf(60, 62, 63, 60, 80, 80), 12, 3, 31, 3, 65),
        Fact(3, listOf(80, 82, 83, 80, 100, 100), 12, 3, 31, 3, 65),
        Fact(4, listOf(39, 52, 43, 65, 60, 50), 10, 10, 31, 3, 66), // Charmander: Fire, Blaze
        Fact(7, listOf(44, 48, 65, 43, 50, 64), 11, 11, 31, 3, 67), // Squirtle: Water, Torrent
        Fact(10, listOf(45, 30, 35, 45, 20, 20), 6, 6, 127, 0, 19), // Caterpie: Bug, Shield Dust
        Fact(16, listOf(40, 45, 40, 56, 35, 35), 0, 2, 127, 3, 51), // Pidgey: Normal/Flying, Keen Eye
        Fact(25, listOf(35, 55, 30, 90, 50, 40), 13, 13, 127, 0, 9), // Pikachu: Electric, Static (Gen 6+ engines buff it)
    )

    /** How many facts must agree before we trust a field offset (60%). */
    private val quorum = (facts.size * 3 + 4) / 5

    private fun baseStats(count: Int): List<BaseStats?> {
        val (b1, b2, b3) = facts.take(3).map { f -> ByteArray(6) { f.stats[it].toByte() } }
        val others = facts.drop(3)
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (p in occurrences(b1)) {
            for (stride in 16..MAX_STRIDE) {
                if (!matches(b2, p + stride) || !matches(b3, p + 2 * stride)) continue
                val base = p - stride // species 1 is at index 1
                // Most of the other reference species must match too (rules out coincidences).
                val agreeing = others.count { f -> f.stats.indices.all { rom.u8(base + f.species * stride + it) == f.stats[it] } }
                if (agreeing * 2 < others.size) continue
                candidates += base to stride
                break
            }
        }
        // The table the code points to is the one in use (hacks leave the old one behind).
        (candidates.firstOrNull { referenced[it.first] } ?: candidates.firstOrNull())?.let { (base, stride) ->
            return readBaseStats(base, stride, count)
        }
        report += "Could not find base stats — level/stat recalculation is disabled"
        return emptyList()
    }

    private fun readBaseStats(base: Int, stride: Int, count: Int): List<BaseStats?> {
        fun at(f: Fact, off: Int) = rom.u8(base + f.species * stride + off)
        /** Offset in [range] where the most facts satisfy [pred], if at least a quorum do. */
        fun vote(range: IntRange, pred: (Fact, Int) -> Boolean): Int? =
            range.map { off -> off to facts.count { pred(it, off) } }
                .filter { it.second >= quorum }
                .maxByOrNull { it.second }?.first

        val typeOff: Int?
        val genderOff: Int?
        val growthOff: Int?
        val abilityOff: Int?
        val abilityWide: Boolean
        val layout: String
        if (stride == 28) {
            // The 28-byte struct is the vanilla layout, kept by FireRed/Emerald hacks (incl. CFRU).
            typeOff = 6; genderOff = 16; growthOff = 19; abilityOff = 22; abilityWide = false
            layout = "vanilla layout"
        } else {
            typeOff = vote(6 until stride - 1) { f, o -> at(f, o) == f.t1 && at(f, o + 1) == f.t2 }
            genderOff = vote(6 until stride) { f, o -> at(f, o) == f.gender }
            growthOff = genderOff?.let { g -> vote(g + 1 until stride) { f, o -> at(f, o) == f.growth } }
            abilityOff = vote(6 until stride - 1) { f, o -> at(f, o) == f.ability }
            // Expansion-style structs store abilities as u16 (high byte 0 for these early abilities).
            abilityWide = abilityOff != null && facts.count { at(it, abilityOff + 1) == 0 } >= quorum
            layout = "custom layout"
        }

        report += "Found base stats: entry size $stride at 0x%06X, %s (types %s, gender %s, growth %s, abilities %s)".format(
            base, layout, typeOff ?: "?", genderOff ?: "?", growthOff ?: "?",
            abilityOff?.let { "$it${if (abilityWide) " (16-bit)" else ""}" } ?: "?",
        )

        return List(count) { species ->
            val e = base + species * stride
            if (species == 0 || e + stride > rom.size) return@List null
            fun u8(off: Int?) = off?.let { rom.u8(e + it) } ?: 0
            fun ability(n: Int) = abilityOff?.let {
                if (abilityWide) rom.u16(e + it + 2 * n) else rom.u8(e + it + n)
            } ?: 0
            BaseStats(
                stats = List(6) { rom.u8(e + it) },
                type1 = u8(typeOff), type2 = typeOff?.let { u8(it + 1) } ?: 0,
                genderRatio = genderOff?.let { u8(it) } ?: 127,
                growthRate = growthOff?.let { u8(it) } ?: 0,
                ability1 = ability(0), ability2 = ability(1),
            )
        }
    }

    // ---- move PP -------------------------------------------------------------------------

    private class MoveData(val pp: List<Int>, val power: List<Int>, val type: List<Int>, val accuracy: List<Int>, val category: List<Int>)

    private fun moveData(count: Int): MoveData? {
        // power, type, accuracy, pp of Pound, Karate Chop, DoubleSlap.
        val p1 = byteArrayOf(40, 0, 100, 35)
        val p2 = byteArrayOf(50, 1, 100, 25)
        val p3 = byteArrayOf(15, 0, 85, 10)
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (p in occurrences(p1)) {
            for (stride in 8..MAX_STRIDE) {
                if (matches(p2, p + stride) && matches(p3, p + 2 * stride)) {
                    candidates += (p - stride) to stride // move 1 is Pound
                    break
                }
            }
        }
        (candidates.firstOrNull { referenced[it.first] } ?: candidates.firstOrNull())?.let { (base, stride) ->
            // base points at move 0's power: power, type, accuracy, pp follow.
            fun field(m: Int, off: Int) = rom.getOrNull(base + off + m * stride)?.toInt()?.and(0xFF) ?: 0
            // Hacks with the physical/special split store it in a spare byte: find the one where known moves agree.
            val known = listOf(1 to 0, 33 to 0, 52 to 1, 85 to 1, 45 to 2, 14 to 2) // Pound, Tackle, Ember, Thunderbolt, Growl, Swords Dance
            val splitAt = (5 until stride).firstOrNull { off -> known.all { (m, c) -> field(m, off) == c } }
            report += "Found move data: entry size $stride" + if (splitAt != null) " (with physical/special split)" else ""
            return MoveData(
                pp = List(count) { field(it, 3) },
                power = List(count) { field(it, 0) },
                type = List(count) { field(it, 1) },
                accuracy = List(count) { field(it, 2) },
                category = if (splitAt != null) List(count) { field(it, splitAt) } else emptyList(),
            )
        }
        report += "Could not find move PP — new moves get 5 PP"
        return null
    }

    // ---- byte search ----------------------------------------------------------------------

    private fun matches(pattern: ByteArray, at: Int): Boolean {
        if (at < 0 || at + pattern.size > rom.size) return false
        for (i in pattern.indices) if (rom[at + i] != pattern[i]) return false
        return true
    }

    /** Offsets of [pattern] (the equal-spacing checks afterwards weed out chance matches). */
    private fun occurrences(pattern: ByteArray, limit: Int = 64): List<Int> {
        val out = mutableListOf<Int>()
        val first = pattern[0]
        var i = 0
        val end = rom.size - pattern.size
        while (i <= end && out.size < limit) {
            if (rom[i] == first && matches(pattern, i)) out += i
            i++
        }
        return out
    }

    private fun ptr(offset: Int): Long = 0x08000000L + offset
    private fun romOffset(pointer: Long): Int? =
        (pointer - 0x08000000L).takeIf { pointer in 0x08000000L until 0x08000000L + rom.size }?.toInt()

    private fun le32(v: Long) = ByteArray(4).also { it.put32(0, v) }

    companion object {
        private const val MAX_STRIDE = 512
    }
}
