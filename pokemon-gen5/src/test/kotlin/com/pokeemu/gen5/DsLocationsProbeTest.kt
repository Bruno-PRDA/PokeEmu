package com.pokeemu.gen5

import com.pokeemu.gen4.FileRomSource
import com.pokeemu.gen4.Gen4Locations
import java.io.File
import kotlin.test.Test

/** Optional: POKEEMU_TEST_ROMS5 prints the location names read from each DS ROM. */
class DsLocationsProbeTest {
    @Test fun probe() {
        val dir = System.getenv("POKEEMU_TEST_ROMS5")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("nds", true) }.orEmpty().sortedBy { it.name }) {
            val code = FileRomSource(f).use { String(it.read(0x0C, 4), Charsets.ISO_8859_1) }
            val names = FileRomSource(f).use { if (Gen5Game.forCode(code) != null) Gen5Locations.read(it) else Gen4Locations.read(it) }
            println("== ${f.name}: ${names.size} names; " + names.entries.filterIndexed { i, _ -> i % 15 == 0 }.joinToString { "${it.key}=${it.value}" })
        }
    }
}
