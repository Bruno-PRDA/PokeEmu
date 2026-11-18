package com.pokeemu.gen3

import com.pokeemu.core.GameRules
import com.pokeemu.core.Slot
import com.pokeemu.core.Transfer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Optional: POKEEMU_TEST_ROMS=<folder of .gba> makes new PokÃ©mon with each ROM's data and saves them. */
class NewMonProbeTest {
    @Test fun newPokemonInRealRoms() {
        val dir = System.getenv("POKEEMU_TEST_ROMS")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("gba", true) }.orEmpty().sortedBy { it.name }) {
            if (f.length() < 0x100000) continue
            val rom = RomScanner(f.readBytes()).scan()
            val real = (1 until rom.speciesNames.size).filter { Transfer.isRealSpecies(rom, it) && rom.base(it) != null }
            val picks = listOf(real.first(), real[real.size / 2], real.last())
            val lead = Pk3.create(1, (54321L shl 16) or 12345L, true).apply { species = real.first(); experience = GameRules.expForLevel(0, 5); setMove(0, 1) }
            val e = SaveEditor(Gen3Save.parse(Gen3SaveFixtures.emerald(listOf(lead))), rom)
            val made = picks.map { s -> e.create(s, 42) as Pk3 }
            made.forEachIndexed { i, pk -> e.insert(Slot.Party(1 + i), pk) }
            val back = Gen3Save.parse(e.toBytes()).party.drop(1)
            println("== ${f.name}: " + back.joinToString { "#${it.species} ${rom.speciesName(it.species)} Lv${it.level} ${it.moves.filter { m -> m != 0 }.map(rom::moveName)}" })
            back.forEachIndexed { i, pk ->
                assertTrue(pk.checksumValid)
                assertEquals(picks[i], pk.species)
                assertEquals(42, pk.level)
            }
        }
    }
}
