package com.pokeemu.saves

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheatsTest {
    @Test fun gbaCodesAreCleanedUpWhateverTheSpacing() {
        // CodeBreaker (8+4) and GameShark/Action Replay (8+8), pasted with odd spacing.
        assertEquals(
            listOf("82025840 0063", "1A2B3C4D 5E6F7A8B"),
            CheatCodes.parse("  82025840 0063\n1a2b3c4d+5e6f7a8b ", Platform.GBA),
        )
        assertEquals(listOf("82025840 0063"), CheatCodes.parse("82025840-0063", Platform.GBA))
    }

    @Test fun dsCodesAreLinesOfTwoWords() {
        assertEquals(
            listOf("94000130 FCFF0000", "021C4F70 0000270F", "D2000000 00000000"),
            CheatCodes.parse("94000130 FCFF0000\n021C4F70 0000270F\nD2000000 00000000", Platform.NDS),
        )
        assertFailsWith<IllegalArgumentException> { CheatCodes.parse("82025840 0063", Platform.NDS) }
    }

    @Test fun badCodesExplainWhatIsWrong() {
        assertTrue("isn't part of a code" in assertFailsWith<IllegalArgumentException> { CheatCodes.parse("8202584G 0063", Platform.GBA) }.message!!)
        assertTrue("missing its second half" in assertFailsWith<IllegalArgumentException> { CheatCodes.parse("82025840", Platform.GBA) }.message!!)
        assertTrue("8 digits" in assertFailsWith<IllegalArgumentException> { CheatCodes.parse("820258 0063", Platform.GBA) }.message!!)
        assertFailsWith<IllegalArgumentException> { CheatCodes.parse("   ", Platform.GBA) }
    }

    @Test fun cheatsAreStoredPerGameAndOnlyEnabledOnesAreApplied() {
        val storage = GameStorage(Files.createTempDirectory("cheats").toFile())
        val store = CheatStore(storage, "game")
        store.save(
            listOf(
                Cheat("Infinite money | max", listOf("82025840 0063", "82025842 0001")),
                Cheat("Walk through walls", listOf("1A2B3C4D 5E6F7A8B"), enabled = false),
            ),
        )
        val loaded = CheatStore(storage, "game").load()
        assertEquals(listOf("Infinite money max", "Walk through walls"), loaded.map { it.name })
        assertEquals(listOf(true, false), loaded.map { it.enabled })
        assertEquals(listOf("82025840 0063+82025842 0001"), store.enabledCodes())
        assertTrue(CheatStore(storage, "other").load().isEmpty())
    }

    @Test fun freezeCodesInEachFormat() {
        assertEquals(listOf("22123456 0000002A"), CheatCodes.freezeCode(Platform.NDS, 0x02123456, 1, 42))
        assertEquals(listOf("12123456 0000BEEF"), CheatCodes.freezeCode(Platform.NDS, 0x02123456, 2, 0xBEEF))
        assertEquals(listOf("02123456 12345678"), CheatCodes.freezeCode(Platform.NDS, 0x02123456, 4, 0x12345678))
        assertEquals(listOf("32025E80 0063"), CheatCodes.freezeCode(Platform.GBA, 0x02025E80, 1, 99))
        assertEquals(listOf("83005FA0 0801"), CheatCodes.freezeCode(Platform.GBA, 0x03005FA0, 2, 0x801))
        assertEquals(listOf("82025E80 5678", "82025E82 1234"), CheatCodes.freezeCode(Platform.GBA, 0x02025E80, 4, 0x12345678))
        // They're valid codes for the Cheats list.
        for (p in listOf(Platform.GBA, Platform.NDS)) {
            val lines = CheatCodes.freezeCode(p, 0x02000000, 4, 7)
            assertEquals(lines, CheatCodes.parse(lines.joinToString("\n"), p))
        }
    }
}
