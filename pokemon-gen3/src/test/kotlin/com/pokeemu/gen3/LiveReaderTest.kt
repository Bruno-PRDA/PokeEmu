package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveReaderTest {
    private val ot = (54321L shl 16) or 12345L

    private fun mon(species: Int, level: Int, pid: Long, otId: Long = ot, encoding: Pk3Encoding = Pk3Encoding.STANDARD) =
        Pk3.create(pid, otId).apply {
            this.species = species
            setMove(0, 33)
            experience = GameRules.expForLevel(0, level)
            setPartyStats(level, listOf(30, 20, 20, 20, 20, 20))
        }.withEncoding(encoding)

    private fun put(ram: ByteArray, at: Int, mons: List<Pk3>) = mons.forEachIndexed { i, pk -> pk.toBytes().copyInto(ram, at + i * 100) }

    /** FireRed-like layout: opponent party 600 bytes before the player's; a save-buffer copy elsewhere. */
    private fun fireRedRam(opponents: List<Pk3>, encoding: Pk3Encoding = Pk3Encoding.STANDARD): ByteArray {
        val ram = ByteArray(0x40000)
        val party = listOf(mon(25, 12, 0x1111, encoding = encoding), mon(7, 15, 0x2222, encoding = encoding))
        // Save-buffer copy, preceded by the party count — must be ignored.
        ram.put32(0x1000 - 4, 2)
        put(ram, 0x1000, party)
        put(ram, 0x2402C, opponents)
        put(ram, 0x24284, party)
        return ram
    }

    @Test fun findsLivePartyNotTheSaveCopy() {
        val reader = LiveReader(ot, Gen3Game.FIRERED_LEAFGREEN)
        assertEquals(0x24284, reader.locate(fireRedRam(emptyList())))
        val state = reader.read(fireRedRam(emptyList()))!!
        assertEquals(listOf(25, 7), state.party.map { it.species })
        assertTrue(state.opponents.isEmpty())
    }

    @Test fun readsOpponentsOnEitherSide() {
        val wild = listOf(mon(16, 3, 0x3333))
        val fr = LiveReader(ot, Gen3Game.FIRERED_LEAFGREEN).read(fireRedRam(wild))!!
        assertEquals(listOf(16), fr.opponents.map { it.species })
        assertEquals(3, fr.opponents[0].level)

        // Emerald-like: opponent party right after the player's.
        val ram = ByteArray(0x40000)
        put(ram, 0x244EC, listOf(mon(25, 12, 0x1111)))
        put(ram, 0x244EC + 600, listOf(mon(19, 4, 0x4444, otId = 999), mon(41, 5, 0x5555, otId = 999)))
        val em = LiveReader(ot, Gen3Game.EMERALD).read(ram)!!
        assertEquals(listOf(19, 41), em.opponents.map { it.species })
        assertEquals(listOf(25), em.party.map { it.species })

        // Emerald wild battle: the wild Pokémon has the player's ID too.
        val ram2 = ByteArray(0x40000)
        put(ram2, 0x244EC, listOf(mon(25, 12, 0x1111)))
        put(ram2, 0x244EC + 600, listOf(mon(263, 2, 0x6666)))
        val wildEm = LiveReader(ot, Gen3Game.EMERALD).read(ram2)!!
        assertEquals(listOf(25), wildEm.party.map { it.species })
        assertEquals(listOf(263), wildEm.opponents.map { it.species })
    }

    @Test fun followsLiveChangesAndPlainEncoding() {
        val reader = LiveReader(ot, Gen3Game.FIRERED_LEAFGREEN, Pk3Encoding.PLAIN)
        val ram = fireRedRam(listOf(mon(16, 3, 0x3333, encoding = Pk3Encoding.PLAIN)), Pk3Encoding.PLAIN)
        assertEquals(listOf(25, 7), reader.read(ram)!!.party.map { it.species })
        // Opponent takes damage: the next read reflects it.
        val hpAt = 0x2402C + 0x56
        ram.put16(hpAt, 5)
        assertEquals(5, reader.read(ram)!!.opponents[0].currentHp)
    }

    @Test fun nothingInBlankRam() {
        assertNull(LiveReader(ot, Gen3Game.EMERALD).read(ByteArray(0x40000)))
        assertNull(LiveReader(ot, Gen3Game.EMERALD).record(ByteArray(200), 0))
    }

    /** Random memory must not produce Pokémon (seen on FireRed: a "Lv84 Doduo" with 13651 HP). */
    @Test fun noFalsePositivesInRandomMemory() {
        val rnd = java.util.Random(1234)
        repeat(4) {
            val ram = ByteArray(0x40000).also(rnd::nextBytes)
            val reader = LiveReader(playerOtId = null, game = Gen3Game.FIRERED_LEAFGREEN, speciesCount = 412, moveCount = 355)
            assertNull(reader.locate(ram), "found a fake party in random bytes")
        }
    }

    @Test fun noOtIdStillFindsRealPartyInBattle() {
        // New game, no save yet: OT unknown. Rival battle: opponent has a different OT.
        val ram = ByteArray(0x40000).also(java.util.Random(99)::nextBytes)
        put(ram, 0x2402C, listOf(mon(4, 5, 0x7777, otId = 0x0BAD_F00DL)))
        put(ram, 0x24284, listOf(mon(1, 5, 0x8888)))
        val state = LiveReader(null, Gen3Game.FIRERED_LEAFGREEN, speciesCount = 412, moveCount = 355).read(ram)!!
        assertEquals(listOf(1), state.party.map { it.species })
        assertEquals(listOf(4), state.opponents.map { it.species })
    }
}
