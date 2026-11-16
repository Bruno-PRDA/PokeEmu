package com.pokeemu.gen3

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Pk3Test {

    /**
     * Builds an encrypted record by hand (independently of Pk3's encoder):
     * personality % 24 == 9 is order "AEMG", so Growth (species) sits at
     * position 3 = offset 0x20 + 36.
     */
    @Test fun decryptsHandBuiltRecord() {
        val pid = 9L + 24 * 1000 // % 24 == 9
        val otId = 0x1234_5678L
        val key = pid xor otId
        val raw = ByteArray(Pk3.BOX_SIZE)
        raw.put32(0, pid)
        raw.put32(4, otId)
        val plain = ByteArray(48) // in stored (shuffled) order
        plain.put16(36, 252) // species word 0 of Growth at position 3
        plain.put16(38, 13) // held item
        // Attacks at position 0: first move = 33 (Tackle)
        plain.put16(0, 33)
        var sum = 0
        for (i in 0 until 48 step 2) sum += plain.u16(i)
        raw.put16(0x1C, sum and 0xFFFF)
        for (w in 0 until 12) raw.put32(0x20 + w * 4, plain.u32(w * 4) xor key)

        val pk = Pk3(raw)
        assertTrue(pk.checksumValid)
        assertEquals(252, pk.species)
        assertEquals(13, pk.heldItem)
        assertEquals(33, pk.move(0))
        assertContentEquals(raw, pk.toBytes(), "decode -> encode must be byte-identical")
    }

    @Test fun editsSurviveRoundTripForEveryOrder() {
        for (order in 0 until 24) {
            val pk = Pk3.create(personality = 0xABCD_0000L + order, otId = 0x0F0F_1234L)
            pk.species = 280; pk.heldItem = 1; pk.experience = 123_456; pk.friendship = 70
            pk.setMove(0, 1); pk.setMove(3, 354); pk.setPp(3, 5)
            pk.setEv(5, 252); pk.setIv(0, 31); pk.setIv(5, 17); pk.abilitySlot = 1
            val back = Pk3(pk.toBytes())
            assertTrue(back.checksumValid, "order $order")
            assertEquals(280, back.species)
            assertEquals(123_456, back.experience)
            assertEquals(listOf(1, 0, 0, 354), back.moves)
            assertEquals(5, back.pp(3))
            assertEquals(252, back.ev(5))
            assertEquals(listOf(31, 0, 0, 0, 0, 17), back.ivs)
            assertEquals(1, back.abilitySlot)
            assertFalse(back.isEgg)
        }
    }

    @Test fun ivBitsDoNotBleedIntoEggOrAbility() {
        val pk = Pk3.create(1, 2)
        for (s in 0 until 6) pk.setIv(s, 31)
        assertFalse(pk.isEgg)
        assertEquals(0, pk.abilitySlot)
        pk.isEgg = true
        assertEquals(List(6) { 31 }, pk.ivs)
    }

    @Test fun corruptedDataFailsChecksum() {
        val bytes = Pk3.create(5, 6).apply { species = 1 }.toBytes()
        bytes[0x30] = (bytes[0x30] + 1).toByte()
        assertFalse(Pk3(bytes).checksumValid)
    }

    @Test fun natureAndShiny() {
        // Shiny: tid ^ sid ^ pidHi ^ pidLo < 8
        val shiny = Pk3.create(personality = 0x1234_5678L, otId = (0x1234L shl 16) or 0x5678L)
        assertTrue(shiny.isShiny)
        assertFalse(Pk3.create(personality = 0x1234_5678L, otId = 0xFFFF_0000L).isShiny)
        assertEquals("Adamant", Pk3.NATURES[Pk3.create(personality = 28, otId = 0).nature])
    }

    @Test fun nicknameUsesGen3Text() {
        val pk = Pk3.create(1, 2)
        pk.nickname = "PIKACHU"
        assertEquals("PIKACHU", Pk3(pk.toBytes()).nickname)
        assertEquals(0xCA, pk.toBytes().u8(0x08)) // 'P'
        assertEquals(0xFF, pk.toBytes().u8(0x08 + 7)) // terminator
    }

    @Test fun boxFormKeepsData() {
        val pk = Pk3.create(77, 88, party = true).apply { species = 25 }
        val box = Pk3(pk.toBoxBytes())
        assertEquals(Pk3.BOX_SIZE, box.toBytes().size)
        assertEquals(25, box.species)
        assertTrue(box.checksumValid)
    }
}
