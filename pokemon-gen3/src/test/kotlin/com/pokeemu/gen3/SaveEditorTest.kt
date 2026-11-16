package com.pokeemu.gen3

import com.pokeemu.core.Slot
import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveEditorTest {
    private val pikachu = BaseStats(listOf(35, 55, 30, 90, 50, 40), 13, 13, 127, 0, 9, 0)
    private val raichu = BaseStats(listOf(60, 90, 55, 100, 90, 80), 13, 13, 127, 0, 9, 0)
    private val rom = RomData(
        speciesNames = List(30) { "MON$it" }.toMutableList().apply { this[25] = "PIKACHU"; this[26] = "RAICHU" },
        moveNames = List(100) { "MOVE$it" }, abilityNames = List(10) { "AB$it" }, itemNames = List(10) { "IT$it" },
        baseStats = List(30) { if (it == 26) raichu else if (it > 0) pikachu else null },
        movePp = List(100) { 10 }, report = emptyList(),
    )

    private fun mon(species: Int, level: Int, pid: Long, party: Boolean = true) =
        Pk3.create(pid, (54321L shl 16) or 12345L, party).apply {
            this.species = species
            experience = GameRules.expForLevel(0, level)
            nickname = rom.speciesName(species)
            setMove(0, 1)
            if (party) setPartyStats(level, GameRules.stats(rom.base(species)!!, level, ivs, evs, nature))
        }

    private fun editor(vararg partyMons: Pk3, boxed: Pk3? = null): SaveEditor {
        val tmp = Gen3SaveFixtures.emerald(partyMons.toList())
        val save = Gen3Save.parse(tmp)
        boxed?.let { save.setBoxSlot(0, 0, it) }
        return SaveEditor(Gen3Save.parse(save.toBytes()), rom)
    }

    @Test fun withdrawComputesPartyStats() {
        val e = editor(mon(25, 10, 1), boxed = mon(25, 50, 2, party = false))
        e.move(Slot.Box(0, 0), Slot.Party(5)) // empty party slot -> appended
        val p = e.partyMembers
        assertEquals(2, p.size)
        assertEquals(50, p[1].level)
        assertEquals(p[1].maxHp, p[1].currentHp, "withdrawn at full HP")
        assertEquals(GameRules.stats(pikachu, 50, p[1].ivs, p[1].evs, p[1].nature), p[1].partyStats)
        assertNull(e.box(0)[0])
    }

    @Test fun depositCompactsPartyAndKeepsAtLeastOne() {
        val e = editor(mon(25, 10, 1), mon(25, 11, 2), mon(25, 12, 3))
        e.move(Slot.Party(0), Slot.Box(1, 5))
        assertEquals(listOf(11, 12), e.partyMembers.map { it.level })
        assertEquals(25, e.box(1)[5]?.species)
        e.delete(Slot.Party(0))
        assertFailsWith<IllegalArgumentException> { e.delete(Slot.Party(0)) }
        assertFailsWith<IllegalArgumentException> { e.move(Slot.Party(0), Slot.Box(2, 0)) }
    }

    @Test fun swapInsideBoxesAndParty() {
        val e = editor(mon(25, 10, 1), mon(26, 20, 2))
        e.move(Slot.Party(0), Slot.Party(1))
        assertEquals(listOf(26, 25), e.partyMembers.map { it.species })
    }

    @Test fun editsSurviveWritingTheSave() {
        val e = editor(mon(25, 10, 7))
        val pk = Pk3(e.partyMembers[0].toBytes())
        e.setSpecies(pk, 26)
        e.setLevel(pk, 42)
        pk.setIv(1, 31)
        e.setMove(pk, 1, 57)
        e.setIdentity(pk, nature = 3, gender = Gender.FEMALE, shiny = true)
        e.replace(Slot.Party(0), pk)

        val reread = Gen3Save.parse(e.toBytes()).party[0]
        assertTrue(reread.checksumValid)
        assertEquals(26, reread.species)
        assertEquals("RAICHU", reread.nickname, "default name follows species")
        assertEquals(42, reread.level)
        assertEquals(31, reread.iv(1))
        assertEquals(57, reread.move(1))
        assertEquals(10, reread.pp(1))
        assertEquals("Adamant", Pk3.NATURES[reread.nature])
        assertTrue(reread.isShiny)
        assertEquals(Gender.FEMALE, GameRules.gender(reread.personality, 127))
        assertEquals(GameRules.stats(raichu, 42, reread.ivs, reread.evs, reread.nature), reread.partyStats)
    }

    @Test fun levelFollowsExperienceWhileEditing() {
        val e = editor(mon(25, 10, 7))
        val pk = Pk3(e.partyMembers[0].toBytes())
        e.setLevel(pk, 30)
        assertEquals(30, e.level(pk), "before the edit is committed")
        assertEquals(10, pk.level, "stored party level only changes on replace()")
    }

    @Test fun nicknamesAreKept() {
        val e = editor(mon(25, 10, 7).apply { nickname = "SPARKY" })
        val pk = Pk3(e.partyMembers[0].toBytes())
        e.setSpecies(pk, 26)
        assertEquals("SPARKY", pk.nickname)
        assertNotEquals(0L, pk.experience)
    }

    @Test fun warningsFlagProblems() {
        val e = editor(mon(25, 10, 7))
        val pk = Pk3(e.partyMembers[0].toBytes())
        assertTrue(e.warnings(pk).isEmpty())
        for (s in 0 until 6) pk.setEv(s, 255)
        pk.setMove(2, 999)
        val w = e.warnings(pk)
        assertTrue(w.any { "EVs total" in it })
        assertTrue(w.any { "Move #999" in it })
    }
}

/** Minimal valid Emerald save with a given party. */
object Gen3SaveFixtures {
    fun emerald(party: List<Pk3>): ByteArray {
        val sections = Array(14) { ByteArray(0x1000) }
        Gen3Text.encode("ASH", 8).copyInto(sections[0], 0)
        sections[0].put16(0x0A, 12345); sections[0].put16(0x0C, 54321)
        sections[0].put32(0xAC, 0x1234_5678L)
        sections[1].put32(0x234, party.size.toLong())
        party.forEachIndexed { i, pk -> pk.toBytes().copyInto(sections[1], 0x238 + i * 100) }
        val file = ByteArray(Gen3Save.FILE_SIZE) { 0xFF.toByte() }
        for (id in 0 until 14) {
            val s = sections[id]
            s.put16(0xFF4, id)
            s.put16(0xFF6, Gen3Save.checksum(s, Gen3Save.dataSize(id)))
            s.put32(0xFF8, 0x08012025L)
            s.put32(0xFFC, 1)
            s.copyInto(file, id * 0x1000)
        }
        return file
    }
}
