package com.pokeemu.gen4

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Gen4LiveReaderTest {
    private val helpers = Gen4Test()

    private fun mon(pid: Long, species: Int, tid: Int = 12345, hp: Int = 20): ByteArray {
        val plain = helpers.plainMon(pid, species, "MON", party = true)
        plain.put16(0x0C, tid)
        plain.put16(0x8E, hp)
        for (s in 1 until 6) plain.put16(0x90 + s * 2, 10)
        return Pk4.fromPlain(plain).toBytes()
    }

    /** A battle party in a used heap block, as the game allocates it. */
    private fun putHeapParty(ram: ByteArray, at: Int, mons: List<ByteArray>, used: Boolean = true) {
        ram.put16(at - 32, if (used) 0x5544 else 0x5246)
        ram.put32(at - 28, 0x5C0)
        putParty(ram, at, mons)
    }

    private fun putParty(ram: ByteArray, at: Int, mons: List<ByteArray>) {
        ram.put32(at, 6)
        ram.put32(at + 4, mons.size.toLong())
        mons.forEachIndexed { i, m -> m.copyInto(ram, at + 8 + i * Pk4.PARTY_SIZE) }
    }

    @Test fun findsPlayerAndOpponentAndFollowsTheLiveCopy() {
        val ram = ByteArray(Gen4LiveReader.RAM_SIZE)
        val mine = listOf(mon(0x1111, 155), mon(0x2222, 25))
        putParty(ram, 0x271FA4, mine) // save buffer
        putParty(ram, 0x38F54C, mine) // second copy
        val reader = Gen4LiveReader(trainerId = 12345, secretId = 54321)
        val calm = reader.read(ram)!!
        assertEquals(listOf(155, 25), calm.party.map { it.species })
        assertTrue(calm.opponents.isEmpty())

        // Battle: a wild Pokémon appears (it carries the player's ID, as wild Pokémon do),
        // and the first copy's Cyndaquil takes damage.
        putHeapParty(ram, 0x100000, listOf(mon(0x9999, 16)))
        putParty(ram, 0x271FA4, listOf(mon(0x1111, 155, hp = 7), mine[1]))
        val battle = reader.read(ram)!!
        assertEquals(listOf(16), battle.opponents.map { it.species })
        assertEquals(7, battle.party[0].currentHp, "shows the copy that changed")

        // Battle over: the block is freed but the bytes stay; the card must go.
        putHeapParty(ram, 0x100000, listOf(mon(0x9999, 16)), used = false)
        assertTrue(reader.read(ram)!!.opponents.isEmpty())
    }

    @Test fun nothingInEmptyRam() {
        assertNull(Gen4LiveReader(1, 2).read(ByteArray(Gen4LiveReader.RAM_SIZE)))
    }

    /** Optional: POKEEMU_TEST_RAM4=<folder of .ram dumps> + POKEEMU_TEST_TID4 prints what real RAM holds. */
    @Test fun realRamIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_RAM4")?.let(::File) ?: return
        val tid = System.getenv("POKEEMU_TEST_TID4")?.toIntOrNull()
        for (f in dir.listFiles { x -> x.extension == "ram" }.orEmpty()) {
            val state = Gen4LiveReader(tid, null).read(f.readBytes())
            assertNotNull(state, f.name)
            println("${f.name}: party=${state.party.map { "#${it.species} ${it.nickname} Lv${it.level} ${it.currentHp}/${it.maxHp}" }} " +
                "opponents=${state.opponents.map { "#${it.species} Lv${it.level} ${it.currentHp}/${it.maxHp}" }}")
        }
    }
}
