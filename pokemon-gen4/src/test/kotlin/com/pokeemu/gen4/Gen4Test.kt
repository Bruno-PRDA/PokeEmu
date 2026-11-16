package com.pokeemu.gen4

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Gen4Test {
    @Test fun crc16CcittKnownValue() {
        // CRC-16/CCITT-FALSE check value for "123456789".
        assertEquals(0x29B1, crc16Ccitt("123456789".toByteArray(), 0, 9))
    }

    @Test fun lcgMatchesPublishedSequence() {
        // Gen 4 PRNG from seed 0: 0x6073 -> high halves 0x0000, 0xE97E, ...
        val lcg = Lcg(0)
        assertEquals(0x0000, lcg.next())
        assertEquals(0xE97E, lcg.next())
    }

    internal fun plainMon(pid: Long, species: Int, nickname: String, party: Boolean, ability: Int = 0): ByteArray {
        val b = ByteArray(if (party) Pk4.PARTY_SIZE else Pk4.BOX_SIZE)
        b.put32(0, pid)
        b.put16(0x08, species); b.put16(0x0A, 0)
        b.put16(0x0C, 12345); b.put16(0x0E, 54321)
        b.put32(0x10, 125)
        b[0x15] = ability.toByte()
        b.put16(0x28, 33) // Tackle
        b.put32(0x38, 31L or (31L shl 5) or (1L shl 31)) // HP/Atk 31, nicknamed
        Gen4Text.encode(nickname, 11).copyInto(b, 0x48)
        Gen4Text.encode("LYRA", 8).copyInto(b, 0x68)
        if (party) {
            b[0x8C] = 5; b.put16(0x8E, 20); b.put16(0x90, 21)
        }
        return b
    }

    @Test fun pk4RoundTripsForEveryShuffleOrder() {
        for (sv in 0 until 24) {
            val pid = (sv.toLong() shl 13) or 0x1234_0000L
            val pk = Pk4.fromPlain(plainMon(pid, 155, "CYNDA", party = true))
            val raw = pk.toBytes()
            val back = Pk4(raw)
            assertTrue(back.checksumValid, "sv $sv")
            assertEquals(155, back.species)
            assertEquals("CYNDA", back.nickname)
            assertEquals("LYRA", back.otName)
            assertEquals(33, back.move(0))
            assertEquals(listOf(31, 31, 0, 0, 0, 0), back.ivs)
            assertEquals(5, back.level)
            assertEquals(20, back.currentHp)
            assertContentEquals(raw, back.toBytes(), "byte-identical re-encode")
            // Really encrypted: species isn't readable at its plain offset.
            assertFalse(raw.u16(0x08) == 155 && raw.u16(0x48) == Gen4Text.encode("CYNDA", 11).u16(0))
        }
    }

    @Test fun corruptedPk4FailsChecksum() {
        val raw = Pk4.fromPlain(plainMon(0xABCDEF, 25, "PIKA", party = false)).toBytes()
        raw[0x30] = (raw[0x30] + 1).toByte()
        assertFalse(Pk4(raw).checksumValid)
    }

    /** Builds a save with valid footers for [layout], partition [newer] having the higher counter. */
    internal fun buildSave(
        layout: Gen4Layout, party: List<ByteArray>, boxMon: ByteArray?, newer: Int = 1,
        fullFooter: Boolean = false, counterBase: Long = 9,
    ): ByteArray {
        val save = ByteArray(Gen4Save.FILE_SIZE) { 0xFF.toByte() }
        for (part in 0..1) {
            val base = part * Gen4Save.PARTITION_SIZE
            val general = ByteArray(layout.generalSize)
            Gen4Text.encode(if (part == newer) "ETHAN" else "OLD", 8).copyInto(general, layout.trainerName)
            general.put16(layout.trainerId, 12345); general.put16(layout.trainerId + 2, 54321)
            general.put32(layout.money, 3000)
            general.put32(layout.partyCount, party.size.toLong())
            party.forEachIndexed { i, p -> p.copyInto(general, layout.party + i * Pk4.PARTY_SIZE) }
            val storage = ByteArray(layout.storageSize)
            boxMon?.copyInto(storage, layout.boxStart + 2 * layout.boxStride + 3 * Pk4.BOX_SIZE) // box 3, slot 4
            for ((block, start) in listOf(general to base, storage to base + layout.storageOffset)) {
                val footer = block.size - layout.footerSize
                block.put32(footer, if (part == newer) counterBase + 1 else counterBase)
                if (fullFooter) {
                    block.put32(footer + 4, block.size.toLong())
                    block.put32(footer + 8, Gen4Layout.FOOTER_MAGIC)
                }
                block.put16(block.size - 2, crc16Ccitt(block, 0, footer))
                block.copyInto(save, start)
            }
        }
        return save
    }

    @Test fun parsesEachGameLayoutAndPicksNewerPartition() {
        val partyMon = Pk4.fromPlain(plainMon(0x1111, 152, "CHIKO", party = true)).toBytes()
        val boxMon = Pk4.fromPlain(plainMon(0x2222, 158, "TOTO", party = false)).toBytes()
        for (layout in Gen4Layout.ALL) for (newer in 0..1) {
            val save = Gen4Save.parse(buildSave(layout, listOf(partyMon), boxMon, newer))
            assertEquals(layout.game, save.game)
            assertEquals("ETHAN", save.trainer.name, "${layout.game} newer=$newer")
            assertEquals(12345, save.trainer.trainerId)
            assertEquals(3000, save.trainer.money)
            assertEquals(listOf(152), save.party.map { it.species })
            assertEquals(158, save.box(2)[3]?.species)
            assertNull(save.box(0)[0])
        }
    }

    @Test fun detectsExpandedHackLayoutFromFooters() {
        // Origin HeartGold: bigger general block, storage at 0xF800 with 24 boxes.
        val layout = Gen4Layout.hgssLike(0xF7CC, 0xF800, 0x18408)
        assertEquals(24, layout.boxCount)
        val partyMon = Pk4.fromPlain(plainMon(0x1111, 152, "CHIKO", party = true)).toBytes()
        val boxMon = Pk4.fromPlain(plainMon(0x2222, 158, "TOTO", party = false)).toBytes()
        val bytes = buildSave(layout, listOf(partyMon), boxMon, newer = 1, fullFooter = true)
        val save = Gen4Save.parse(bytes)
        assertEquals(Gen4Game.HEARTGOLD_SOULSILVER_EXPANDED, save.game)
        assertEquals("ETHAN", save.trainer.name)
        assertEquals(158, save.box(2)[3]?.species)
        assertNull(save.box(23)[0])

        // Stale vanilla-sized blocks with an older counter must not win over the hack's newer ones.
        val stale = buildSave(Gen4Layout.HEARTGOLD_SOULSILVER, emptyList(), null, newer = 0, fullFooter = true, counterBase = 1)
        stale.copyInto(bytes, 0, 0, Gen4Save.PARTITION_SIZE)
        assertEquals(Gen4Game.HEARTGOLD_SOULSILVER_EXPANDED, Gen4Save.parse(bytes).game)
    }

    @Test fun rejectsGarbage() {
        assertFailsWith<Gen4SaveException> { Gen4Save.parse(ByteArray(1000)) }
        assertFailsWith<Gen4SaveException> { Gen4Save.parse(ByteArray(Gen4Save.FILE_SIZE) { 0xFF.toByte() }) }
    }

    @Test fun messageFileRoundTrip() {
        // Build an encrypted bank with the documented scheme and check decode.
        val strings = listOf("", "Bulbasaur", "Mr. Mime")
        val seed = 0x1234
        val header = 4 + strings.size * 8
        val out = ByteArray(header + strings.sumOf { it.length * 2 })
        out.put16(0, strings.size); out.put16(2, seed)
        var at = header
        strings.forEachIndexed { i, s ->
            val k = (seed * 0x2FD * (i + 1)) and 0xFFFF
            val key = k.toLong() or (k.toLong() shl 16)
            out.put32(4 + i * 8, at.toLong() xor key); out.put32(8 + i * 8, s.length.toLong() xor key)
            val plain = Gen4Text.encode(s + " ", s.length + 1)
            var ck = (0x91BD3 * (i + 1)) and 0xFFFF
            for (j in s.indices) { out.put16(at + j * 2, plain.u16(j * 2) xor ck); ck = (ck + 0x493D) and 0xFFFF }
            at += s.length * 2
        }
        assertEquals(strings, Gen4Messages.decode(out))
    }

    /** Optional: POKEEMU_TEST_ROMS4=<folder of .nds> prints names read from real DS ROMs. */
    @Test fun realRomsIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_ROMS4")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("nds", true) }.orEmpty()) {
            val data = FileRomSource(f).use { Gen4RomData.scan(it) }
            println("${f.name}: ${data.speciesNames.size} species, ${data.moveNames.size} moves")
            println("   species 1..6: ${(1..6).map(data::species)}  last: ${data.speciesNames.takeLast(4)}")
            println("   moves 1..4: ${(1..4).map(data::move)}  last: ${data.moveNames.takeLast(3)}")
            val unknown = data.speciesNames.count { '?' in it }
            println("   names with unknown characters: $unknown")
            println("   494..500: ${(494..500).map(data::species)} 650..652: ${(650..652).map(data::species)} 900..912: ${(900..912).map(data::species)}")
            assertEquals("bulbasaur", data.species(1).lowercase())
            data.report.forEach { println("   report: $it") }
            for (name in listOf("Charmander", "Clefairy", "Shedinja", "Sylveon", "Eternatus")) {
                val id = data.speciesNames.indexOfFirst { it.equals(name, true) }
                val b = data.base(id)
                println("   $name #$id: ${b?.stats} ${b?.let { data.typeName(it.type1) + "/" + data.typeName(it.type2) }} ab=${b?.let { data.abilityName(it.ability1) + "/" + data.abilityName(it.ability2) }} g=${b?.genderRatio} growth=${b?.growthRate}")
            }
            FileRomSource(f).use { src ->
                val fs = NdsFileSystem(src)
                for (path in listOf("a/0/1/1")) fs.read(path)?.let { b ->
                    val n = Narc.parse(b)
                    println("   $path: ${n.size} entries, #1=${n[1].joinToString(" ") { "%02x".format(it) }}, #500 size=${if (n.size > 500) n[500].size else -1}")
                }
                println("   files: ${fs.paths.size}, dirs a/0/1/*: ${fs.paths.keys.filter { it.startsWith("a/0/1/") }.sorted()}")
            }
            println("   items 1..4: ${(1..4).map(data::itemName)}; pp Pound=${data.basePp(1)} Tackle=${data.basePp(33)}; personal entries=${data.baseStats.size}")
        }
    }

    /** Optional: POKEEMU_TEST_SAVES4=<folder of .sav> prints what real Gen 4 saves contain. */
    @Test fun realSavesIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_SAVES4")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("sav", true) }.orEmpty()) {
            val save = Gen4Save.parse(f.readBytes())
            println("${f.name}: ${save.game.label} ${save.trainer} fallback=${save.usedFallback}")
            for (pk in save.party) {
                println("   #${pk.species} ${pk.nickname} Lv${pk.level} ${Pk4.NATURES[pk.nature]} ivs=${pk.ivs} moves=${pk.moves} ability=${pk.ability} shiny=${pk.isShiny} nicknamed=${pk.isNicknamed} stats=${pk.partyStats} hp=${pk.currentHp} ok=${pk.checksumValid}")
                assertTrue(pk.checksumValid)
                assertContentEquals(pk.toBytes(), Pk4(pk.toBytes()).toBytes())
            }
        }
    }
}
