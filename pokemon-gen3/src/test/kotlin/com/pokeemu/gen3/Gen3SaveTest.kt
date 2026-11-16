package com.pokeemu.gen3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds synthetic Gen 3 saves section by section. */
private class SaveBuilder(val layout: SaveLayout = SaveLayout.EMERALD) {
    val sections = Array(14) { ByteArray(0x1000) }

    fun trainer(name: String, tid: Int, sid: Int, hours: Int = 12, minutes: Int = 34) = apply {
        Gen3Text.encode(name, 8).copyInto(sections[0], 0)
        sections[0].put16(0x0A, tid)
        sections[0].put16(0x0C, sid)
        sections[0].put16(0x0E, hours)
        sections[0].put8(0x10, minutes)
        when (layout.game) {
            Gen3Game.RUBY_SAPPHIRE -> sections[0].put32(0xAC, 0)
            Gen3Game.FIRERED_LEAFGREEN -> { sections[0].put32(0xAC, 1); sections[0].put32(0xF20, SECURITY_KEY) }
            Gen3Game.EMERALD -> sections[0].put32(0xAC, SECURITY_KEY)
        }
    }

    fun money(amount: Long) = apply {
        val key = if (layout.securityKey != null) SECURITY_KEY else 0
        sections[1].put32(layout.money, amount xor key)
    }

    fun party(vararg pks: Pk3) = apply {
        sections[1].put32(layout.teamSize, pks.size.toLong())
        pks.forEachIndexed { i, pk -> pk.toBytes().copyInto(sections[1], layout.team + i * 100) }
    }

    /** Writes one block; [rotation] shifts the physical section order like the game does. */
    fun writeBlock(file: ByteArray, block: Int, index: Long, rotation: Int) {
        for (p in 0 until 14) {
            val id = (p + rotation) % 14
            val sec = sections[id].copyOf()
            sec.put16(0xFF4, id)
            sec.put16(0xFF6, Gen3Save.checksum(sec, Gen3Save.dataSize(id)))
            sec.put32(0xFF8, 0x08012025L)
            sec.put32(0xFFC, index)
            sec.copyInto(file, block * 0xE000 + p * 0x1000)
        }
    }

    fun build(index: Long = 7, block: Int = 0, rotation: Int = 3): ByteArray =
        ByteArray(Gen3Save.FILE_SIZE) { 0xFF.toByte() }.also { writeBlock(it, block, index, rotation) }

    companion object {
        const val SECURITY_KEY = 0x5A5A_1234L
    }
}

class Gen3SaveTest {
    private fun mon(species: Int, pid: Long) = Pk3.create(pid, 0x0001_0002L).apply { this.species = species }

    @Test fun readsTrainerMoneyAndParty() {
        for (layout in listOf(SaveLayout.RUBY_SAPPHIRE, SaveLayout.EMERALD, SaveLayout.FIRERED_LEAFGREEN)) {
            val bytes = SaveBuilder(layout).trainer("MAY", 12345, 54321).money(98765)
                .party(mon(277, 1), mon(280, 2)).build()
            val save = Gen3Save.parse(bytes)
            assertEquals(layout.game, save.game, "detected game")
            assertEquals("MAY", save.trainer.name)
            assertEquals(12345, save.trainer.trainerId)
            assertEquals(54321, save.trainer.secretId)
            assertEquals(12, save.trainer.hours)
            assertEquals(98765, save.money)
            assertEquals(listOf(277, 280), save.party.map { it.species })
        }
    }

    @Test fun picksNewestBlock() {
        val old = SaveBuilder().trainer("OLD", 1, 1)
        val new = SaveBuilder().trainer("NEW", 1, 1)
        val bytes = old.build(index = 4, block = 0)
        new.writeBlock(bytes, block = 1, index = 5, rotation = 9)
        assertEquals("NEW", Gen3Save.parse(bytes).trainer.name)
    }

    @Test fun writesToOtherBlockAndKeepsOriginal() {
        val original = SaveBuilder().trainer("BRENDAN", 7, 8).money(100).party(mon(25, 99)).build(index = 41, block = 0)
        val save = Gen3Save.parse(original)
        save.money = 4242
        save.setPartyMember(0, Pk3(save.party[0].toBytes()).apply { species = 26 })
        val written = save.toBytes()

        // Block 0 (the original save) is untouched.
        assertContentEquals(original.copyOfRange(0, 0xE000), written.copyOfRange(0, 0xE000))
        // Everything after both blocks (Hall of Fame etc.) is untouched.
        assertContentEquals(original.copyOfRange(0x1C000, 0x20000), written.copyOfRange(0x1C000, 0x20000))

        val reread = Gen3Save.parse(written)
        assertEquals(4242, reread.money)
        assertEquals(26, reread.party[0].species)
        assertTrue(reread.party[0].checksumValid)
        assertEquals("BRENDAN", reread.trainer.name)
    }

    @Test fun unchangedRewriteKeepsAllSectionData() {
        val bytes = SaveBuilder().trainer("ASH", 1, 2).party(mon(1, 3)).build()
        val again = Gen3Save.parse(Gen3Save.parse(bytes).toBytes()).toBytes()
        // Two rewrites land back in block 0 with index +2; data must be identical by section id.
        val a = Gen3Save.parse(bytes)
        val b = Gen3Save.parse(again)
        assertEquals(a.trainer, b.trainer)
        assertEquals(a.party.map { it.toBytes().toList() }, b.party.map { it.toBytes().toList() })
    }

    @Test fun boxesRoundTripAcrossSectionBoundaries() {
        val save = Gen3Save.parse(SaveBuilder().trainer("RED", 1, 1).build())
        // Box 3 slot 13 straddles sections 5 and 6 (offset 4 + 103*80 = 8244, section size 3968).
        save.setBoxSlot(3, 13, mon(150, 0xDEADL))
        save.setBoxSlot(13, 29, mon(151, 0xBEEFL)) // very last slot, in section 13
        val reread = Gen3Save.parse(save.toBytes())
        assertEquals(150, reread.box(3)[13]?.species)
        assertEquals(151, reread.box(13)[29]?.species)
        assertNull(reread.box(0)[0])
        reread.setBoxSlot(3, 13, null)
        assertNull(Gen3Save.parse(reread.toBytes()).box(3)[13])
    }

    /** Like Pokémon Odyssey (CFRU): all sections checksummed over 0xFF0, extra data past 0xF80. */
    @Test fun extendedChecksumSavesReadAndWriteBack() {
        val b = SaveBuilder(SaveLayout.FIRERED_LEAFGREEN).trainer("NYX", 1, 2).party(mon(25, 5))
        b.sections[1][0xFE1] = 0x42 // hack-specific data past the vanilla size
        val file = ByteArray(Gen3Save.FILE_SIZE) { 0xFF.toByte() }
        for (p in 0 until 14) {
            val sec = b.sections[p].copyOf()
            sec.put16(0xFF4, p)
            sec.put16(0xFF6, Gen3Save.checksum(sec, 0xFF0))
            sec.put32(0xFF8, 0x08012025L)
            sec.put32(0xFFC, 1)
            sec.copyInto(file, 0xE000 + p * 0x1000)
        }
        val save = Gen3Save.parse(file)
        assertTrue(save.extendedChecksums)
        assertEquals("NYX", save.trainer.name)
        save.money = 777
        val written = save.toBytes()
        val reread = Gen3Save.parse(written)
        assertTrue(reread.extendedChecksums, "written back in the same scheme")
        assertEquals(777, reread.money)
        assertEquals(0x42, written[1 * 0x1000 + 0xFE1].toInt() and 0xFF, "extra data preserved (section 1 now at physical slot 1 of block 0)")
    }

    /** Pokémon Odyssey stores records unencrypted in G-A-E-M order with checksum 0. */
    @Test fun plainEncodedPokemonAreDetectedAndKept() {
        fun plain(species: Int, pid: Long): Pk3 = Pk3.create(pid, 0x0001_0002L).apply {
            this.species = species; experience = 125; setMove(0, 84)
        }.withEncoding(Pk3Encoding.PLAIN)
        val bytes = SaveBuilder(SaveLayout.FIRERED_LEAFGREEN).trainer("NYX", 1, 2).party(plain(353, 0xC7D85B5BL), plain(354, 0x7FBFF629L)).build()
        // Raw check: species readable without decryption, checksum 0.
        val save = Gen3Save.parse(bytes)
        assertEquals(Pk3Encoding.PLAIN, save.pkEncoding)
        assertEquals(listOf(353, 354), save.party.map { it.species })
        assertEquals(84, save.party[0].move(0))

        // A standard-encoded Pokémon written into this save is converted to plain.
        save.setBoxSlot(0, 0, mon(25, 0x1234L))
        val reread = Gen3Save.parse(save.toBytes())
        assertEquals(Pk3Encoding.PLAIN, reread.pkEncoding)
        assertEquals(25, reread.box(0)[0]?.species)
        assertEquals(0, reread.box(0)[0]!!.toBytes().u16(0x1C), "checksum stays 0 like the game writes it")
        // And back to standard for .pk3 export.
        val exported = Pk3(reread.party[0].withEncoding(Pk3Encoding.STANDARD).toBytes())
        assertTrue(exported.checksumValid)
        assertEquals(353, exported.species)
    }

    @Test fun rejectsBadInput() {
        assertFailsWith<Gen3SaveException> { Gen3Save.parse(ByteArray(0x8000)) }
        assertFailsWith<Gen3SaveException> { Gen3Save.parse(ByteArray(Gen3Save.FILE_SIZE) { 0xFF.toByte() }) }
        val bytes = SaveBuilder().trainer("X", 1, 1).build()
        bytes[0x10] = (bytes[0x10] + 1).toByte() // corrupt a section's data
        assertFailsWith<Gen3SaveException> { Gen3Save.parse(bytes) }
    }

    @Test fun moneyIsBounded() {
        val save = Gen3Save.parse(SaveBuilder().trainer("X", 1, 1).build())
        assertFailsWith<IllegalArgumentException> { save.money = 1_000_000 }
    }

    /**
     * Optional: checks real saves. Put .sav files in a folder and run with
     * POKEEMU_TEST_SAVES=<folder>. Each must parse, and decode→encode of every
     * Pokémon must be byte-identical.
     */
    @Test fun realSavesIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_SAVES")?.let(::File) ?: return
        val files = dir.listFiles { f -> f.extension.equals("sav", true) }.orEmpty()
        for (f in files) {
            val bytes = f.readBytes()
            val save = Gen3Save.parse(bytes)
            println("${f.name}: ${save.game.label}, ${save.trainer}, party ${save.party.map { it.species }}")
            for (pk in save.party) {
                assertTrue(pk.checksumValid, "${f.name}: party checksum")
                assertContentEquals(pk.toBytes(), Pk3(pk.toBytes(), pk.encoding).toBytes())
            }
            for (b in 0 until Gen3Save.BOX_COUNT) for (pk in save.box(b).filterNotNull()) {
                assertContentEquals(pk.toBytes(), Pk3(pk.toBytes(), pk.encoding).toBytes(), "${f.name}: box $b")
            }
            val reread = Gen3Save.parse(save.toBytes())
            assertEquals(save.trainer, reread.trainer)
            assertEquals(save.money, reread.money)
        }
    }
}
