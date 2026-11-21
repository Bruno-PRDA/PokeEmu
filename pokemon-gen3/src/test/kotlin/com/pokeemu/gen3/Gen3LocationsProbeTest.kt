package com.pokeemu.gen3

import java.io.File
import kotlin.test.Test

/** Optional: POKEEMU_TEST_ROMS prints the location names read from each ROM. */
class Gen3LocationsProbeTest {
    @Test fun probe() {
        val dir = System.getenv("POKEEMU_TEST_ROMS")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("gba", true) }.orEmpty().sortedBy { it.name }) {
            val t = System.currentTimeMillis()
            val names = Gen3Locations.read(f.readBytes())
            println("== ${f.name}: ${names.size} names in ${System.currentTimeMillis() - t} ms; " + names.entries.filterIndexed { i, _ -> i % 12 == 0 }.joinToString { "${it.key}=${it.value}" })
        }
    }
}
