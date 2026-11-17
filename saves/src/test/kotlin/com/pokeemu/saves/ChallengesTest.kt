package com.pokeemu.saves

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChallengesTest {
    @Test fun settingsAndLogArePerSaveSlot() {
        val storage = GameStorage(Files.createTempDirectory("ch").toFile())
        val main = ChallengeStore(storage, "game", "Main")
        assertFalse(main.active)
        main.shinyOdds = 100
        main.nuzlocke = true
        main.add(ChallengeStore.Kind.ENCOUNTER, 1, "Wild Pidgey Lv 3")
        main.add(ChallengeStore.Kind.DEATH, 42, "Sparky (Pikachu) fainted")

        val again = ChallengeStore(storage, "game", "Main")
        assertEquals(100, again.shinyOdds)
        assertTrue(again.nuzlocke)
        assertEquals(listOf("Wild Pidgey Lv 3", "Sparky (Pikachu) fainted"), again.log().map { it.text })
        assertEquals(setOf(42L), again.dead())

        val other = ChallengeStore(storage, "game", "Nuzlocke run")
        assertFalse(other.active)
        assertTrue(other.log().isEmpty())
    }
}
