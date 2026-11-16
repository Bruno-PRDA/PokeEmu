package com.pokeemu.saves

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RomHeaderTest {
    private fun fakeRom(title: String, code: String): ByteArray {
        val rom = ByteArray(0x200)
        title.toByteArray().copyInto(rom, 0xA0)
        code.toByteArray().copyInto(rom, 0xAC)
        "01".toByteArray().copyInto(rom, 0xB0)
        rom[0xB2] = 0x96.toByte()
        rom[0xBC] = 1
        return rom
    }

    @Test fun parsesTitleAndGameCode() {
        val h = RomHeader.parse(fakeRom("POKEMON EMER", "BPEE"))!!
        assertEquals("POKEMON EMER", h.title)
        assertEquals("BPEE", h.gameCode)
        assertEquals("01", h.makerCode)
        assertEquals(1, h.version)
    }

    @Test fun trimsPaddingFromShortTitles() {
        assertEquals("FIRE", RomHeader.parse(fakeRom("FIRE", "BPRE"))!!.title)
    }

    @Test fun rejectsNonGbaData() {
        assertNull(RomHeader.parse(ByteArray(0x200)))
        assertNull(RomHeader.parse(ByteArray(16)))
    }

    @Test fun romIdIsStableAndContentBased() {
        val a = fakeRom("A", "AAAA")
        assertEquals(romId(a), romId(a.copyOf()))
        assertEquals(16, romId(a).length)
        assertNotEquals(romId(a), romId(fakeRom("B", "AAAA")))
    }
}
