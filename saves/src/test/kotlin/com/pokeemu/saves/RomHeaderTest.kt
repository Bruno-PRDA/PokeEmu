package com.pokeemu.saves

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test fun parsesNdsHeaderAndChecksCrc() {
        val rom = ByteArray(0x200)
        "POKEMON PL".toByteArray().copyInto(rom, 0x00)
        "CPUE".toByteArray().copyInto(rom, 0x0C)
        "01".toByteArray().copyInto(rom, 0x10)
        val crc = NdsHeader.crc16(rom, 0, 0x15E)
        rom[0x15E] = crc.toByte(); rom[0x15F] = (crc shr 8).toByte()
        val h = NdsHeader.parse(rom)!!
        assertEquals("POKEMON PL", h.title)
        assertEquals("CPUE", h.gameCode)
        assertEquals(Platform.NDS, h.platform)
        rom[0x20] = 1 // header changed, CRC no longer matches
        assertNull(NdsHeader.parse(rom))
    }

    @Test fun pokemonToolsOnlyForGamesTheyUnderstand() {
        fun ds(code: String) = RomHeader("POKEMON", code, "01", 0, Platform.NDS)
        assertTrue(RomHeader("POKEMON FIRE", "BPRE", "01", 0).pokemonTools)
        for (code in listOf("ADAE", "APAE", "CPUE", "IPKE", "IPGE", "IPKJ")) assertTrue(ds(code).pokemonTools, code)
        // Black, White, Black 2: they play, without the Gen 4 tools.
        for (code in listOf("IRBO", "IRAO", "IREO", "IRDO")) assertFalse(ds(code).pokemonTools, code)
    }

    @Test fun crc16KnownValue() {
        // CRC-16/MODBUS check value for "123456789".
        assertEquals(0x4B37, NdsHeader.crc16("123456789".toByteArray(), 0, 9))
    }

    @Test fun platformFromFileName() {
        assertEquals(Platform.NDS, Platform.forFileName("Pokemon Platinum.NDS"))
        assertEquals(Platform.GBA, Platform.forFileName("x.gba"))
        assertNull(Platform.forFileName("readme.txt"))
    }

    @Test fun streamingRomIdMatchesInMemoryId() {
        val bytes = ByteArray(3 shl 20) { (it * 31).toByte() }
        val f = java.io.File.createTempFile("rom", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        assertEquals(romId(bytes), romId(f))
    }

    @Test fun romIdIsStableAndContentBased() {
        val a = fakeRom("A", "AAAA")
        assertEquals(romId(a), romId(a.copyOf()))
        assertEquals(16, romId(a).length)
        assertNotEquals(romId(a), romId(fakeRom("B", "AAAA")))
    }
}
