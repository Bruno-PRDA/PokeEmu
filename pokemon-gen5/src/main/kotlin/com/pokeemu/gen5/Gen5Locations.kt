package com.pokeemu.gen5

import com.pokeemu.gen4.Gen4Locations
import com.pokeemu.gen4.Narc
import com.pokeemu.gen4.NdsFileSystem
import com.pokeemu.gen4.RomSource

/**
 * The names of the places a Gen 5 Pokémon can be met: the text file (in
 * a/0/0/2) listing the most "Route n" lines.
 */
object Gen5Locations {
    fun read(rom: RomSource): Map<Int, String> {
        val narc = NdsFileSystem(rom).read("a/0/0/2")?.takeIf(Narc::isNarc)?.let(Narc::parse) ?: return emptyMap()
        val best = (0 until narc.size).mapNotNull { Gen5Text.decode(narc[it]) }
            .maxByOrNull(Gen4Locations::routeCount)?.takeIf { Gen4Locations.routeCount(it) >= 5 } ?: return emptyMap()
        return best.withIndex().associate { (i, s) -> i to s }
    }
}
