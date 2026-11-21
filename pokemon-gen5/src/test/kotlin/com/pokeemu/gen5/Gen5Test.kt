package com.pokeemu.gen5

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Gen5Test {
    /** A decrypted party record with the given fields. */
    private fun plain(pid: Long, species: Int, nick: String, nature: Int, party: Boolean = true): ByteArray {
        val b = ByteArray(if (party) Pk5.PARTY_SIZE else Pk5.BOX_SIZE)
        b.put32(0, pid)
        b.put16(0x08, species)
        b.put16(0x0C, 12345); b.put16(0x0E, 54321)
        b.put32(0x10, 1000)
        b[0x15] = 65
        b.put16(0x28, 33); b[0x30] = 35
        b[0x41] = nature.toByte()
        Pk5.encodeString(nick, 11).copyInto(b, 0x48)
        Pk5.encodeString("TOTO", 8).copyInto(b, 0x68)
        if (party) { b[0x8C] = 10; b.put16(0x8E, 30); b.put16(0x90, 31) }
        return b
    }

    @Test fun recordRoundTripsAndReadsFields() {
        val pk = Pk5.fromPlain(plain(0x89ABCDEFL, 495, "Snivy♀", nature = 15))
        val back = Pk5(pk.toBytes())
        assertTrue(back.checksumValid)
        assertEquals(495, back.species)
        assertEquals("Snivy♀", back.nickname)
        assertEquals("TOTO", back.otName)
        assertEquals(15, back.nature, "Gen 5 keeps the nature in its own byte")
        assertEquals(33, back.move(0))
        assertEquals(10, back.level)
        assertEquals(30, back.currentHp)
        assertContentEquals(pk.toBytes(), back.toBytes())
        assertEquals(Pk5.BOX_SIZE, back.toBoxForm().toBytes().size)
        assertTrue(Pk5(back.toBoxForm().toBytes()).checksumValid)
    }

    @Test fun damagedRecordIsDetected() {
        val bytes = Pk5.fromPlain(plain(0x11223344L, 25, "Pika", 3)).toBytes()
        bytes[0x20] = (bytes[0x20].toInt() xor 0x55).toByte()
        assertFalse(Pk5(bytes).checksumValid)
    }

    /** A blank save with valid checksums (Black/White layout). */
    private fun blankSave(): ByteArray {
        val s = ByteArray(Gen5Save.SIZE)
        Pk5.encodeString("HILDA", 8).copyInto(s, 0x19404)
        s.put16(0x19414, 1111); s.put16(0x19416, 2222)
        fun seal(at: Int, length: Int, tableSlot: Int?) {
            val c = Gen5Save.crc16(s, at, length)
            s.put16(at + length + 2, c)
            tableSlot?.let { s.put16(it, c) }
        }
        for (b in 0 until 24) seal(0x400 + 0x1000 * b, 0xFF0, 0x23F00 + 2 * (1 + b))
        seal(0x18E00, 0x534, 0x23F34)
        val t = Gen5Save.crc16(s, 0x23F00, 0x8C)
        s.put16(0x23F9A, t) // the table's checksum is 0x0E past its end
        // The backup copy, the same.
        s.copyInto(s, 0x24000, 0, 0x24000)
        return s
    }

    @Test fun saveWritesPartyAndBoxesWithValidChecksums() {
        val save = Gen5Save.parse(blankSave())
        assertEquals(Gen5Game.BLACK_WHITE, save.game)
        assertEquals("HILDA", save.trainer.name)
        assertEquals(1111, save.trainer.trainerId)
        assertTrue(save.party.isEmpty())
        val mon = Pk5.fromPlain(plain(0x01020304L, 501, "Oshawott", 7))
        val boxes = List(24) { b -> List<Pk5?>(30) { s -> if (b == 3 && s == 5) mon.toBoxForm() else null } }
        val written = save.toBytes(listOf(mon), boxes)
        val again = Gen5Save.parse(written)
        assertEquals(1, again.party.size)
        assertEquals(501, again.party[0].species)
        assertEquals("Oshawott", again.party[0].nickname)
        assertEquals(501, again.box(3)[5]?.species)
        assertEquals(null, again.box(0)[0])
        // Every block checksum and its table copy agree.
        for (b in 0 until 24) {
            val at = 0x400 + 0x1000 * b
            val c = Gen5Save.crc16(written, at, 0xFF0)
            assertEquals(c, written.u16(at + 0xFF2))
            assertEquals(c, written.u16(0x23F00 + 2 * (1 + b)))
        }
        assertEquals(Gen5Save.crc16(written, 0x18E00, 0x534), written.u16(0x23F34))
        assertEquals(Gen5Save.crc16(written, 0x23F00, 0x8C), written.u16(0x23F9A))
        // The backup copy was updated too.
        assertContentEquals(written.copyOfRange(0x400, 0x19400), written.copyOfRange(0x24400, 0x3D400))
        assertEquals(written.u16(0x23F9A), written.u16(0x47F9A))
    }
}

/** Optional: POKEEMU_TEST_SAVES5=<folder of .sav> reads real Gen 5 saves and rewrites them unchanged. */
class Gen5RealSaveTest {
    @Test fun realSavesIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_SAVES5")?.let { java.io.File(it) } ?: return
        for (f in dir.listFiles { x -> x.extension.equals("sav", true) }.orEmpty()) {
            val bytes = f.readBytes()
            val save = Gen5Save.parse(bytes)
            println("${f.name}: ${save.game.label} ${save.trainer} party=${save.party.map { "#${it.species} ${it.nickname} Lv${it.level} ok=${it.checksumValid}" }}")
            save.party.forEach { assertTrue(it.checksumValid) }
            val boxes = List(save.boxCount) { save.box(it) }
            val again = save.toBytes(save.party, boxes)
            assertContentEquals(bytes.copyOf(Gen5Save.SIZE), again, "an unchanged rewrite gives the same file")
        }
    }
}
