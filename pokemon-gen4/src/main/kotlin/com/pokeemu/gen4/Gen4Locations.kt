package com.pokeemu.gen4

/**
 * The names of the places a Gen 4 Pokémon can be met: the text file listing
 * the most "Route n" lines (a met location number is a line in it).
 */
object Gen4Locations {
    private val TEXT_ARCHIVES = listOf("a/0/2/7", "msgdata/pl_msg.narc", "msgdata/msg.narc")

    fun read(rom: RomSource): Map<Int, String> {
        val fs = NdsFileSystem(rom)
        for (path in TEXT_ARCHIVES) {
            val narc = fs.read(path)?.takeIf(Narc::isNarc)?.let(Narc::parse) ?: continue
            val best = (0 until narc.size).mapNotNull { Gen4Messages.decode(narc[it]) }
                .maxByOrNull(::routeCount)?.takeIf { routeCount(it) >= 5 } ?: continue
            return best.withIndex().associate { (i, s) -> i to s }
        }
        return emptyMap()
    }

    fun routeCount(lines: List<String>) = lines.count { Regex("""^Route [0-9]+$""").matches(it.trim()) }
}
