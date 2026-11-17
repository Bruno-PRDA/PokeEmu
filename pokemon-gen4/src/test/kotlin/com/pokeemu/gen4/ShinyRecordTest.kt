package com.pokeemu.gen4

import com.pokeemu.core.GameRules
import com.pokeemu.core.ShinyPid
import com.pokeemu.gen3.Pk3
import com.pokeemu.gen3.shinyRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShinyRecordTest {
    private val helpers = Gen4Test()

    @Test fun rerolledValuesAreShinyAndKeepNatureAndLowByte() {
        val r = Random(7)
        repeat(500) {
            val pid = r.nextLong(0, 0x1_0000_0000L)
            val tid = r.nextInt(65536)
            val sid = r.nextInt(65536)
            val shiny = ShinyPid.reroll(pid, tid, sid, r)
            assertTrue(ShinyPid.isShiny(shiny, tid, sid))
            assertEquals(pid % 25, shiny % 25, "nature")
            assertEquals(pid and 0xFF, shiny and 0xFF, "gender / ability bit")
        }
    }

    @Test fun gbaRecordStaysValid() {
        val pk = Pk3.create(0x1234_5678, (54321L shl 16) or 12345L, party = true).apply {
            species = 25
            experience = GameRules.expForLevel(0, 12)
            setMove(0, 33)
            setPartyStats(12, listOf(30, 20, 15, 25, 18, 17))
        }
        assertFalse(pk.isShiny)
        val back = Pk3(pk.shinyRecord())
        assertTrue(back.checksumValid)
        assertTrue(back.isShiny)
        assertEquals(25, back.species)
        assertEquals(pk.nature, back.nature)
        assertEquals(33, back.move(0))
        assertEquals(12, back.level)
        assertEquals(pk.currentHp, back.currentHp)
    }

    @Test fun everyCopyInRamIsRewritten() {
        val plain = helpers.plainMon(0x2222_3333, 158, "TOTO", party = true, ability = 67)
        plain[0x8E] = 21
        val pk = Pk4.fromPlain(plain)
        val party = pk.toBytes()
        val box = Pk4(party.copyOfRange(0, Pk4.BOX_SIZE)).toBytes()
        val ram = ByteArray(2048)
        party.copyInto(ram, 100)
        box.copyInto(ram, 600)
        // A battle struct: the bare personality value among other fields.
        for (i in 0 until 4) ram[1200 + i] = (pk.personality shr (8 * i)).toByte()
        val old = pk.personality
        val new = ShinyPid.reroll(old, pk.trainerId, pk.secretId)
        val writes = ShinyPid.rewrites(ram, old, new, listOf(Pk4.PARTY_SIZE, Pk4.BOX_SIZE)) { b ->
            Pk4(b).takeIf { ShinyPid.sameMon(it, pk) }?.apply { personality = new }?.toBytes()
        }
        assertEquals(listOf(100 to Pk4.PARTY_SIZE, 600 to Pk4.BOX_SIZE, 1200 to 4), writes.map { it.first to it.second.size })
        for ((at, bytes) in writes) bytes.copyInto(ram, at)
        val partyBack = Pk4(ram.copyOfRange(100, 100 + Pk4.PARTY_SIZE))
        assertTrue(partyBack.checksumValid && partyBack.isShiny)
        assertEquals(21, partyBack.currentHp)
        assertTrue(Pk4(ram.copyOfRange(600, 600 + Pk4.BOX_SIZE)).isShiny)
        assertEquals(new, (0 until 4).fold(0L) { acc, i -> acc or ((ram[1200 + i].toLong() and 0xFF) shl (8 * i)) })
    }

    @Test fun dsRecordStaysValid() {
        val plain = helpers.plainMon(0x2222_3333, 158, "TOTO", party = true, ability = 67)
        plain[0x8E] = 21 // current HP
        val pk = Pk4.fromPlain(plain)
        val back = Pk4(pk.shinyRecord())
        assertTrue(back.checksumValid)
        assertTrue(back.isShiny)
        assertEquals(158, back.species)
        assertEquals(pk.nature, back.nature)
        assertEquals(pk.moves, back.moves)
        assertEquals(21, back.currentHp)
    }
}
