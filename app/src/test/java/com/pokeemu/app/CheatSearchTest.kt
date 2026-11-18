package com.pokeemu.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheatSearchTest {
    private val region = CheatSearch.Region(0x02000000L, 0x1000)

    @Test fun narrowsDownToTheMoneyAddress() {
        val ram = ByteArray(0x1000) { (it * 7).toByte() }
        fun put16(at: Int, v: Int) { ram[at] = v.toByte(); ram[at + 1] = (v shr 8).toByte() }
        put16(0x420, 3000) // money
        val read = { _: CheatSearch.Region -> ram.copyOf() }
        val s = CheatSearch(listOf(region), 2)
        assertTrue(s.start(read))
        assertEquals(0x800L, s.count)
        // Spend money: it decreased, everything else stayed.
        put16(0x420, 2500)
        s.filter(read, CheatSearch.Compare.DECREASED)
        assertEquals(1L, s.count)
        val r = s.results(read).single()
        assertEquals(0x02000420L, r.address)
        assertEquals(2500L, r.value)
        assertEquals(3000L, r.previous)
    }

    @Test fun exactValueAndUnchangedKeepTheRightOnes() {
        val ram = ByteArray(0x1000)
        ram[0x10] = 99; ram[0x20] = 99; ram[0x30] = 5
        val read = { _: CheatSearch.Region -> ram.copyOf() }
        val s = CheatSearch(listOf(region), 1)
        s.start(read)
        s.filter(read, CheatSearch.Compare.EQUAL, 99)
        assertEquals(listOf(0x02000010L, 0x02000020L), s.results(read).map { it.address })
        ram[0x20] = 98
        s.filter(read, CheatSearch.Compare.UNCHANGED)
        assertEquals(listOf(0x02000010L), s.results(read).map { it.address })
        assertEquals(2, s.steps)
    }

    @Test fun fourByteValuesAreLittleEndian() {
        val s = CheatSearch(listOf(region), 4)
        assertEquals(listOf<Byte>(0x78, 0x56, 0x34, 0x12), s.bytesOf(0x12345678).toList())
        assertEquals(0xFFFFFFFFL, s.maxValue)
    }
}
