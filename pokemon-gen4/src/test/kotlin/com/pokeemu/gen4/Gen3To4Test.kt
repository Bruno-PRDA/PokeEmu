package com.pokeemu.gen4

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Slot
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import com.pokeemu.gen3.Pk3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Gen3To4Test {
    private val helpers = Gen4Test()
    private val treecko = BaseStats(listOf(40, 45, 35, 70, 65, 55), 12, 12, 31, 3, 65, 0)

    // A DS game: Treecko is #252 (it's #277 in GBA games), Tackle #33.
    private val data = Gen4RomData(
        speciesNames = List(500) { i -> when (i) { 152 -> "Chikorita"; 158 -> "Totodile"; 252 -> "Treecko"; else -> "" } },
        moveNames = List(500) { when (it) { 33 -> "Tackle"; 45 -> "Growl"; else -> "" } },
        itemNames = List(200) { if (it == 17) "Potion" else "" },
        baseStats = List(500) { i ->
            when (i) {
                152 -> BaseStats(listOf(45, 49, 65, 45, 49, 65), 12, 12, 31, 3, 65, 0)
                158 -> BaseStats(listOf(50, 65, 64, 43, 44, 48), 11, 11, 31, 3, 67, 0)
                252 -> treecko
                else -> null
            }
        },
        movePp = List(500) { if (it == 33) 35 else if (it == 45) 40 else 0 },
    )

    private fun editor(): Gen4SaveEditor {
        val party = Pk4.fromPlain(helpers.plainMon(0x1111, 152, "CHIKO", party = true, ability = 65)).toBytes()
        val box = Pk4.fromPlain(helpers.plainMon(0x2222, 158, "TOTO", party = false, ability = 67)).toBytes()
        return Gen4SaveEditor(Gen4Save.parse(helpers.buildSave(Gen4Layout.hgssLike(0xF7CC, 0xF800, 0x18408), listOf(party), box, newer = 1, fullFooter = true)), data)
    }

    /** A GBA Treecko (#277 in Gen 3), level 20, with a nickname unless [nickname] is its name. */
    private fun gbaTreecko(nickname: String = "LEAFY", egg: Boolean = false): StoredMon {
        val pk = Pk3.create(personality = 0x0000_00F0, otId = (54321L shl 16) or 12345L, party = false).apply {
            species = 277
            experience = GameRules.expForLevel(3, 20)
            this.nickname = nickname
            setMove(0, 33); setPp(0, 35)
            setMove(1, 45); setPp(1, 40)
            heldItem = 13 // Gen 3's Potion number
            for (s in 0 until 6) setIv(s, 31)
            setEv(1, 252)
            isEgg = egg
        }
        return StoredMon(
            id = "t", format = "pk3", bytes = pk.toBoxBytes(), speciesName = "TREECKO", nickname = nickname, level = 20,
            shiny = false, moveNames = listOf("TACKLE", "GROWL"), itemName = "POTION", fromGame = "FireRed", fromRomId = "fr",
            storedAt = 0,
        )
    }

    @Test fun gbaPokemonArriveInADsGameLikeThroughPalPark() {
        Gen3To4.register()
        val e = editor()
        val result = Transfer.adapt(gbaTreecko(), e, "hg")
        e.insert(Slot.Box(0, 5), result.mon)

        val back = Gen4SaveEditor(Gen4Save.parse(e.toBytes()), data)
        val pk = back[Slot.Box(0, 5)] as Pk4
        assertTrue(pk.checksumValid)
        assertEquals(252, pk.species, "matched by name, not by Gen 3 number")
        assertEquals("LEAFY", pk.nickname)
        assertTrue(pk.isNicknamed)
        assertEquals(20, back.level(pk))
        assertEquals(20, pk.metLevel)
        assertEquals(listOf(33, 45, 0, 0), pk.moves)
        assertEquals(35, pk.pp(0))
        assertEquals(17, pk.heldItem, "Potion, by name")
        assertEquals(List(6) { 31 }, pk.ivs)
        assertEquals(252, pk.ev(1))
        assertEquals(0x0000_00F0L, pk.personality)
        assertEquals(12345, pk.trainerId)
        assertEquals(54321, pk.secretId)
        assertEquals(65, pk.ability) // Overgrow: Treecko's only ability
        assertTrue(pk.isFemale == (0xF0 < 31)) // personality 0xF0 vs gender ratio 31: male
        assertFalse(pk.isFemale)
    }

    @Test fun unNicknamedPokemonTakeTheNewGamesName() {
        Gen3To4.register()
        val e = editor()
        val pk = Transfer.adapt(gbaTreecko(nickname = "TREECKO"), e, "hg").mon as Pk4
        assertEquals("Treecko", pk.nickname)
        assertFalse(pk.isNicknamed)
    }

    @Test fun eggsStayBehind() {
        Gen3To4.register()
        assertFailsWith<IllegalArgumentException> { Transfer.adapt(gbaTreecko(egg = true), editor(), "hg") }
    }
}
