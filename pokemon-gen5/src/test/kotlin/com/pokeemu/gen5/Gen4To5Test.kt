package com.pokeemu.gen5

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Slot
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import com.pokeemu.gen3.Pk3
import com.pokeemu.gen4.Gen3To4
import com.pokeemu.gen4.Pk4
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Gen4To5Test {
    // Black/White numbering: Treecko #252, Tackle #33, Growl #45, Potion #17. Overgrow 65, Unburden 84 (hidden).
    private val data = Gen5RomData(
        speciesNames = List(650) { i -> when (i) { 252 -> "Treecko"; 498 -> "Tepig"; else -> "" } },
        moveNames = List(560) { when (it) { 33 -> "Tackle"; 45 -> "Growl"; else -> "" } },
        itemNames = List(600) { if (it == 17) "Potion" else "" },
        baseStats = List(650) { i ->
            when (i) {
                252 -> BaseStats(listOf(40, 45, 35, 70, 65, 55), 12, 12, 31, 3, 65, 0)
                498 -> BaseStats(listOf(65, 63, 45, 45, 45, 45), 10, 10, 31, 3, 66, 0)
                else -> null
            }
        },
        moveStats = List(560) { if (it == 33) intArrayOf(0, 50, 0, 100, 35) else if (it == 45) intArrayOf(2, 0, 0, 100, 40) else IntArray(5) },
        hiddenAbilities = List(650) { if (it == 252) 84 else 0 },
    )

    /** A GBA Treecko (#277 in Gen 3), level 20. */
    private fun gbaTreecko(nickname: String = "LEAFY", egg: Boolean = false): Pk3 =
        Pk3.create(personality = 0x0000_00F0, otId = (54321L shl 16) or 12345L, party = false).apply {
            species = 277
            experience = GameRules.expForLevel(3, 20)
            this.nickname = nickname
            setMove(0, 33); setPp(0, 35)
            setMove(1, 45); setPp(1, 40)
            heldItem = 13
            for (s in 0 until 6) setIv(s, 31)
            setEv(1, 252)
            isEgg = egg
        }

    private fun stored(pk: Pk3, nickname: String) = StoredMon(
        id = "t", format = "pk3", bytes = pk.toBoxBytes(), speciesName = "TREECKO", nickname = nickname, level = 20,
        shiny = false, moveNames = listOf("TACKLE", "GROWL"), itemName = "POTION", fromGame = "FireRed", fromRomId = "fr",
        storedAt = 0,
    )

    @Test fun gen4RecordBecomesAValidGen5RecordKeepingWhatTransferKeeps() {
        val pk4Bytes = Gen3To4.convert(gbaTreecko().toBoxBytes(), LocalDate.of(2026, 11, 18))
        val pk4 = Pk4(pk4Bytes)
        val pk5 = Pk5(Gen4To5.convert(pk4Bytes, LocalDate.of(2026, 11, 18)))
        assertTrue(pk5.checksumValid)
        assertEquals(pk4.personality, pk5.personality)
        assertEquals(pk4.trainerId, pk5.trainerId)
        assertEquals(pk4.secretId, pk5.secretId)
        assertEquals(pk4.experience, pk5.experience)
        assertEquals(pk4.moves, pk5.moves)
        assertEquals(pk4.ivs, pk5.ivs)
        assertEquals(pk4.evs, pk5.evs)
        assertEquals((pk4.personality % 25).toInt(), pk5.nature, "nature written to its own byte")
        assertEquals("LEAFY", pk5.nickname)
        assertEquals(pk4.otName, pk5.otName)
        assertEquals(pk4.originVersion, pk5.originVersion, "game of origin kept")
        assertEquals(70, pk5.friendship)
    }

    @Test fun eggsAreRefused() {
        val egg = Gen3To4.convert(gbaTreecko().toBoxBytes(), LocalDate.of(2026, 11, 18)).let { Pk4(it) }
        // Mark it as an egg through a fresh GBA record (Gen3To4 itself refuses eggs).
        assertFailsWith<IllegalArgumentException> { Gen3To4.convert(gbaTreecko(egg = true).toBoxBytes()) }
        assertFalse(egg.isEgg)
    }

    /** A blank Black/White save (layout as in Gen5Test). */
    private fun blankSave(): ByteArray {
        val s = ByteArray(Gen5Save.SIZE)
        Pk5.encodeString("HILDA", 8).copyInto(s, 0x19404)
        fun seal(at: Int, length: Int, slot: Int) { val c = Gen5Save.crc16(s, at, length); s.put16(at + length + 2, c); s.put16(slot, c) }
        // One party member so the party isn't empty.
        val tepig = ByteArray(Pk5.PARTY_SIZE).also { it.put32(0, 0x12345678L); it.put16(0x08, 498); it.put16(0x28, 33); it[0x41] = 3 }
        s[0x18E04] = 1
        Pk5.fromPlain(tepig).toBytes().copyInto(s, 0x18E08)
        for (b in 0 until 24) {
            for (slotIdx in 0 until 30) Pk5.fromPlain(ByteArray(Pk5.BOX_SIZE)).toBytes().copyInto(s, 0x400 + 0x1000 * b + slotIdx * Pk5.BOX_SIZE)
            seal(0x400 + 0x1000 * b, 0xFF0, 0x23F00 + 2 * (1 + b))
        }
        seal(0x18E00, 0x534, 0x23F34)
        s.put16(0x23F9A, Gen5Save.crc16(s, 0x23F00, 0x8C))
        s.copyInto(s, 0x24000, 0, 0x24000)
        return s
    }

    @Test fun gbaPokemonGoesIntoABlackWhiteSave() {
        Gen4To5.register()
        val editor = Gen5SaveEditor(Gen5Save.parse(blankSave()), data)
        val result = Transfer.adapt(stored(gbaTreecko(), "LEAFY"), editor, targetRomId = "white")
        val pk = result.mon as Pk5
        assertEquals(252, pk.species, "matched by name: GBA #277 is #252 here")
        assertEquals(listOf(33, 45, 0, 0), pk.moves)
        assertEquals(17, pk.heldItem, "Potion matched by name")
        assertEquals(65, pk.ability, "the regular ability, not the hidden one")
        assertFalse(pk.hasHiddenAbility)
        assertEquals("LEAFY", pk.nickname)
        assertTrue(pk.isNicknamed)
        assertEquals(Gen4To5.POKE_TRANSFER, Pk5(pk.toBytes()).let { pk.plainBytes().let { b -> (b[0x80].toInt() and 0xFF) or ((b[0x81].toInt() and 0xFF) shl 8) } })
        editor.insert(Slot.Box(0, 0), pk)
        val written = Gen5Save.parse(editor.toBytes())
        val back = written.box(0)[0]!!
        assertTrue(back.checksumValid)
        assertEquals(252, back.species)
        assertEquals(20, GameRules.levelForExp(3, back.experience))
        assertEquals(1, written.party.size, "party untouched")
    }

    @Test fun anUnnicknamedPokemonTakesTheNewGamesName() {
        Gen4To5.register()
        val editor = Gen5SaveEditor(Gen5Save.parse(blankSave()), data)
        val pk = Transfer.adapt(stored(gbaTreecko(nickname = "TREECKO"), "TREECKO"), editor, "white").mon as Pk5
        assertEquals("Treecko", pk.nickname)
        assertFalse(pk.isNicknamed)
    }

    @Test fun gen5PokemonCantGoBackToAnOlderGame() {
        Gen4To5.register()
        assertEquals(null, Transfer.converters["pk5" to "pk4"])
        assertEquals("Black/White", Transfer.generationLabel("pk5"))
    }
}
