package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameRulesTest {
    @Test fun level100ExperienceMatchesPublishedTotals() {
        // Medium Fast, Erratic, Fluctuating, Medium Slow, Fast, Slow
        assertEquals(listOf(1_000_000L, 600_000, 1_640_000, 1_059_860, 800_000, 1_250_000), (0..5).map { GameRules.expForLevel(it, 100) })
    }

    @Test fun lowLevelValues() {
        assertEquals(9, GameRules.expForLevel(3, 2)) // Medium Slow level 2
        assertEquals(0, GameRules.expForLevel(3, 1))
        assertEquals(15, GameRules.expForLevel(1, 2)) // Erratic level 2
        assertEquals(4, GameRules.expForLevel(2, 2)) // Fluctuating level 2
    }

    @Test fun levelForExpIsInverse() {
        for (growth in 0..5) for (level in 1..100) {
            val exp = GameRules.expForLevel(growth, level)
            assertEquals(level, GameRules.levelForExp(growth, exp), "growth $growth level $level")
            if (level < 100) assertEquals(level, GameRules.levelForExp(growth, GameRules.expForLevel(growth, level + 1) - 1))
        }
    }

    @Test fun statsMatchPublishedExample() {
        // Bulbapedia's worked example: Lv78 Adamant Garchomp. Order: HP, Atk, Def, Spe, SpA, SpD.
        val garchomp = BaseStats(listOf(108, 130, 95, 102, 80, 85), 16, 4, 127, 5, 8, 0)
        val stats = GameRules.stats(garchomp, 78, ivs = listOf(24, 12, 30, 5, 16, 23), evs = listOf(74, 190, 91, 23, 48, 84), nature = 3)
        assertEquals(listOf(289, 278, 193, 171, 135, 171), stats)
    }

    @Test fun natureModifiers() {
        // Lonely: +Atk -Def. Hardy: neutral.
        assertEquals(listOf(10, 11, 9, 10, 10, 10), (0..5).map { GameRules.natureMultiplierTenths(1, it) })
        assertEquals(List(6) { 10 }, (0..5).map { GameRules.natureMultiplierTenths(0, it) })
    }

    @Test fun findPersonalityHitsEveryTarget() {
        val r = Random(42)
        for (nature in 0..24) for (shiny in listOf(false, true)) for (gender in listOf(Gender.MALE, Gender.FEMALE)) {
            val pid = GameRules.findPersonality(nature, gender, genderRatio = 31, shiny = shiny, trainerId = 12345, secretId = 54321, random = r)
            assertEquals(nature, (pid % 25).toInt())
            assertEquals(gender, GameRules.gender(pid, 31))
            assertEquals(shiny, GameRules.isShiny(pid, 12345, 54321))
        }
    }

    @Test fun impossibleGenderIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            GameRules.findPersonality(0, Gender.FEMALE, genderRatio = 0, shiny = false, trainerId = 1, secretId = 2)
        }
        assertTrue(GameRules.gender(0x12345678, 255) == Gender.GENDERLESS)
    }
}
