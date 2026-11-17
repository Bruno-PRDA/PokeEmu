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
}
