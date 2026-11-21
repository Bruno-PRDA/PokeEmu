package com.pokeemu.gen3

/**
 * The names of the places a Gen 3 Pokémon can be met (its met location is a
 * map section number). Found by content, so hacks work: the table of name
 * pointers with the most "ROUTE n" entries. Ruby/Sapphire/Emerald keep
 * { x, y, width, height, name } per section from section 0; FireRed/LeafGreen
 * keep bare name pointers starting at Pallet Town (section 0x58).
 */
object Gen3Locations {
    private const val ROM_BASE = 0x08000000L
    private const val KANTO_START = 0x58

    /** Where FireRed/LeafGreen code keeps the address of its name table (hacks rarely move the code). */
    private val FRLG_TABLE_REFS = listOf(0xC0C94, 0xC4DB8)

    fun read(rom: ByteArray): Map<Int, String> {
        if (String(rom, 0xAC, 3, Charsets.ISO_8859_1) in setOf("BPR", "BPG")) frlgTable(rom)?.let { return it }
        val routes = HashSet<Long>()
        for (p in 1 until rom.size - 8) {
            if (rom.u8(p) == 0xCC && rom.u8(p - 1) == Gen3Text.TERMINATOR && isRoute(rom, p)) routes += ROM_BASE + p
        }
        if (routes.isEmpty()) return longestNameList(rom)
        var best: Pair<Int, Map<Int, String>>? = null
        val tried = HashSet<Long>()
        var q = 0
        while (q + 4 <= rom.size) {
            if (rom.u32(q) in routes) {
                for ((stride, at, base) in listOf(Triple(8, 4, 0), Triple(4, 0, KANTO_START))) {
                    // Walk back to the table's first entry, then read it forwards.
                    var start = q - at
                    while (start - stride >= 0 && entry(rom, start - stride, stride, at) != null) start -= stride
                    if (!tried.add(start.toLong() * 16 + stride)) continue
                    val names = LinkedHashMap<Int, String>()
                    var i = 0
                    var hits = 0
                    while (start + (i + 1) * stride <= rom.size && base + i < 256) {
                        val name = entry(rom, start + i * stride, stride, at) ?: break
                        if (rom.u32(start + i * stride + at) in routes) hits++
                        names[base + i] = name
                        i++
                    }
                    if (hits > (best?.first ?: 2)) best = hits to names
                }
            }
            q += 4
        }
        return best?.second ?: longestNameList(rom)
    }

    /** The table the FireRed/LeafGreen code points to (names may use any characters). */
    private fun frlgTable(rom: ByteArray): Map<Int, String>? {
        for (ref in FRLG_TABLE_REFS) {
            if (ref + 4 > rom.size) continue
            val table = (rom.u32(ref) - ROM_BASE).toInt()
            if (table < 0 || table >= rom.size) continue
            val names = LinkedHashMap<Int, String>()
            var i = 0
            while (KANTO_START + i < 256 && table + 4 * i + 4 <= rom.size) {
                val ptr = rom.u32(table + 4 * i) - ROM_BASE
                if (ptr < 0 || ptr >= rom.size) break
                names[KANTO_START + i] = name(rom, ptr.toInt(), lenient = true) ?: break
                i++
            }
            if (names.size >= 60) return names
        }
        return null
    }

    /** "ROUTE 101" (any case) at [p]. */
    private fun isRoute(rom: ByteArray, p: Int): Boolean {
        val s = Gen3Text.decode(rom, p, minOf(12, rom.size - p))
        return Regex("""^(ROUTE|Route) [0-9]+$""").matches(s)
    }

    /** The name an entry points to, or null if it isn't one. Placeholders ({FD xx}) are left out. */
    private fun entry(rom: ByteArray, at: Int, stride: Int, ptrAt: Int): String? {
        if (stride == 8 && (0 until 4).any { rom.u8(at + it) > 64 }) return null
        val ptr = rom.u32(at + ptrAt) - ROM_BASE
        if (ptr < 0 || ptr >= rom.size - 1) return null
        return name(rom, ptr.toInt())
    }

    private fun name(rom: ByteArray, p: Int, lenient: Boolean = false): String? {
        val out = StringBuilder()
        var i = p
        while (i < rom.size && i - p < 40) {
            val b = rom.u8(i)
            when {
                b == Gen3Text.TERMINATOR -> return out.toString().replace(Regex(" +"), " ").trim().ifEmpty { "—" }
                b == 0xFC || b == 0xFD -> i++ // a control code and its argument
                Gen3Text.isChar(b) -> out.append(Gen3Text.decode(rom, i, 1))
                lenient -> {}
                else -> return null
            }
            i++
        }
        return null
    }

    /**
     * Hacks that rename every route: the longest run of pointers to short names
     * (FireRed-style table, so numbered from Pallet Town's section on FireRed/LeafGreen bases).
     */
    private fun longestNameList(rom: ByteArray): Map<Int, String> {
        val base = if (String(rom, 0xAC, 3, Charsets.ISO_8859_1) in setOf("BPR", "BPG")) KANTO_START else 0
        var best = emptyList<String>()
        var q = 0
        while (q + 4 <= rom.size) {
            val names = ArrayList<String>()
            var r = q
            while (r + 4 <= rom.size && names.size < 400) {
                val ptr = rom.u32(r) - ROM_BASE
                if (ptr < 0 || ptr >= rom.size) break
                val n = name(rom, ptr.toInt())?.takeIf { it.length in 2..20 && it[0].isLetter() } ?: break
                names += n; r += 4
            }
            if (names.size > best.size) best = names
            q = if (names.isEmpty()) q + 4 else r
        }
        return if (best.size >= 60) best.withIndex().associate { (i, n) -> base + i to n } else emptyMap()
    }
}
