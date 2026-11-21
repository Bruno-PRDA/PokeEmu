package com.pokeemu.gen4

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import com.pokeemu.core.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Gen4SaveEditorTest {
    private val helpers = Gen4Test()

    // Chikorita (152) and Totodile (158) with real base stats; 1000 is a "fakemon" with no data.
    private val data = Gen4RomData(
        speciesNames = List(1001) { i -> when (i) { 152 -> "Chikorita"; 153 -> "Bayleef"; 158 -> "Totodile"; 1000 -> "Fakemon"; else -> "" } },
        moveNames = List(500) { if (it == 33) "Tackle" else if (it == 10) "Scratch" else "" },
        abilityNames = List(100) { if (it == 65) "Overgrow" else if (it == 67) "Torrent" else "" },
        baseStats = List(1001) { i ->
            when (i) {
                152 -> BaseStats(listOf(45, 49, 65, 45, 49, 65), 12, 12, 31, 3, 65, 0)
                153 -> BaseStats(listOf(60, 62, 80, 60, 63, 80), 12, 12, 31, 3, 65, 0)
                158 -> BaseStats(listOf(50, 65, 64, 43, 44, 48), 11, 11, 31, 3, 67, 0)
                else -> null
            }
        },
        movePp = List(500) { if (it == 33) 35 else 0 },
    )

    /** [slotStyle] mimics Origin HeartGold, which keeps a slot number (1) instead of the ability id. */
    private fun editorFor(layout: Gen4Layout = Gen4Layout.hgssLike(0xF7CC, 0xF800, 0x18408), slotStyle: Boolean = false): Gen4SaveEditor {
        val party = Pk4.fromPlain(helpers.plainMon(0x1111, 152, "CHIKO", party = true, ability = if (slotStyle) 1 else 65)).toBytes()
        val box = Pk4.fromPlain(helpers.plainMon(0x2222, 158, "TOTO", party = false, ability = if (slotStyle) 1 else 67)).toBytes()
        val bytes = helpers.buildSave(layout, listOf(party), box, newer = 1, fullFooter = true)
        return Gen4SaveEditor(Gen4Save.parse(bytes), data)
    }

    @Test fun editsSurviveSaveRoundTripWithValidChecksums() {
        val e = editorFor()
        val pk = e[Slot.Party(0)]!!.copy()
        e.setLevel(pk, 30)
        for (s in 0 until 6) pk.setIv(s, 31)
        pk.setEv(1, 252)
        e.setNickname(pk, "Leafy")
        e.setMove(pk, 1, 33)
        e.replace(Slot.Party(0), pk)

        val bytes = e.toBytes()
        e.verify(bytes)
        val back = Gen4Save.parse(bytes)
        val mon = back.party.single()
        assertTrue(mon.checksumValid)
        assertEquals(30, mon.level)
        assertEquals("Leafy", mon.nickname)
        assertTrue(mon.isNicknamed)
        assertEquals(listOf(31, 31, 31, 31, 31, 31), mon.ivs)
        assertEquals(33, mon.move(1))
        assertEquals(35, mon.pp(1))
        val expected = GameRules.stats(data.base(152)!!, 30, mon.ivs, mon.evs, mon.nature)
        assertEquals(expected, mon.partyStats)
        assertEquals(expected[0] - 1, mon.currentHp, "the 1 HP of damage it had is kept")
        assertEquals(158, back.box(2)[3]?.species, "untouched box Pokémon still there")
    }

    @Test fun movingBetweenPartyAndBoxRecalculatesStats() {
        val e = editorFor()
        e.move(Slot.Box(2, 3), Slot.Party(1))
        assertNull(e[Slot.Box(2, 3)])
        val toto = e.partyMembers[1]
        assertEquals(158, toto.species)
        assertEquals(e.level(toto), toto.level)
        assertEquals(GameRules.stats(data.base(158)!!, toto.level!!, toto.ivs, toto.evs, toto.nature), toto.partyStats)

        e.move(Slot.Party(0), Slot.Box(0, 0))
        assertEquals(1, e.partyMembers.size)
        assertFailsWith<IllegalArgumentException> { e.move(Slot.Party(0), Slot.Box(0, 5)) } // last party member

        val back = Gen4Save.parse(e.toBytes())
        assertEquals(listOf(158), back.party.map { it.species })
        assertEquals(152, back.box(0)[0]?.species)
        assertNull(back.box(2)[3])
    }

    @Test fun speciesWithoutRomDataCantJoinTheParty() {
        val e = editorFor()
        val fake = e[Slot.Box(2, 3)]!!.copy()
        fake.species = 1000
        e.replace(Slot.Box(2, 3), fake)
        assertFailsWith<IllegalArgumentException> { e.move(Slot.Box(2, 3), Slot.Party(1)) }
        // ...but it can still be moved within the PC and saved.
        e.move(Slot.Box(2, 3), Slot.Box(5, 0))
        assertEquals(1000, Gen4Save.parse(e.toBytes()).box(5)[0]?.species)
    }

    @Test fun identityChangesKeepGenderFlagsConsistent() {
        val e = editorFor()
        val pk = e[Slot.Party(0)]!!.copy()
        e.setIdentity(pk, nature = 3, gender = Gender.FEMALE, shiny = true)
        assertEquals(3, pk.nature)
        assertTrue(pk.isShiny)
        assertEquals(Gender.FEMALE, e.gender(pk))
        assertTrue(pk.isFemale)
        e.replace(Slot.Party(0), pk)
        val mon = Gen4Save.parse(e.toBytes()).party.single()
        assertTrue(mon.checksumValid)
        assertEquals("Adamant", Pk4.NATURES[mon.nature])
        assertTrue(mon.isShiny && mon.isFemale)
    }

    @Test fun speciesChangeRenamesDefaultNameAndKeepsLevel() {
        val e = editorFor()
        val pk = e[Slot.Party(0)]!!.copy()
        e.setNickname(pk, "Chikorita") // default name → not nicknamed
        e.setLevel(pk, 20)
        e.setSpecies(pk, 153)
        assertEquals("Bayleef", pk.nickname)
        assertEquals(20, e.level(pk))
        assertEquals(65, pk.ability)
    }

    @Test fun hackAbilitySlotsAreLeftAlone() {
        val e = editorFor(slotStyle = true)
        val pk = e[Slot.Party(0)]!!.copy()
        assertEquals(-1, e.abilityIndex(pk))
        assertFailsWith<IllegalArgumentException> { e.setAbility(pk, 0) }
        e.setSpecies(pk, 153)
        assertEquals(1, pk.ability, "slot value kept")

        val vanilla = editorFor()
        assertEquals(0, vanilla.abilityIndex(vanilla[Slot.Party(0)]!!))
    }

    @Test fun exportImportRoundTrip() {
        val e = editorFor()
        val bytes = e.exportBytes(e[Slot.Party(0)]!!)
        assertEquals(Pk4.BOX_SIZE, bytes.size)
        val imported = e.parseImport(bytes)
        e.insert(Slot.Box(0, 1), imported)
        assertEquals(152, Gen4Save.parse(e.toBytes()).box(0)[1]?.species)
        bytes[0x40] = (bytes[0x40] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { e.parseImport(bytes) }
    }

    @Test fun worksOnVanillaLayoutsToo() {
        for (layout in Gen4Layout.ALL) {
            val e = editorFor(layout)
            e.move(Slot.Box(2, 3), Slot.Party(1))
            val back = Gen4Save.parse(e.toBytes())
            assertEquals(layout.game, back.game)
            assertEquals(listOf(152, 158), back.party.map { it.species })
        }
    }

    @Test fun newPokemonSurvivesASaveAndRefusesUnknownSpecies() {
        val e = editorFor()
        val pk = e.create(158, 20) as Pk4
        assertTrue(pk.checksumValid)
        assertEquals(158, pk.species)
        assertEquals(20, pk.metLevel)
        assertEquals("Totodile", pk.nickname)
        assertEquals(e[Slot.Party(0)]!!.trainerId, pk.trainerId)
        assertEquals(java.time.LocalDate.now(), pk.metDate)
        e.insert(Slot.Party(1), pk)
        val back = Gen4Save.parse(e.toBytes()).party
        assertEquals(158, back[1].species)
        assertEquals(20, back[1].level)
        assertEquals(GameRules.stats(data.base(158)!!, 20, back[1].ivs, back[1].evs, back[1].nature), back[1].partyStats)
        assertFailsWith<IllegalArgumentException> { e.create(1000, 5) }
    }
}
