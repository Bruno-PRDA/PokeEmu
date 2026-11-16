package com.pokeemu.gen3

import kotlin.random.Random

enum class Gender { MALE, FEMALE, GENDERLESS }

/** Gen 3 formulas, matching the games' integer math. */
object GameRules {
    const val MAX_LEVEL = 100
    const val MAX_EV_TOTAL = 510

    val GROWTH_NAMES = listOf("Medium Fast", "Erratic", "Fluctuating", "Medium Slow", "Fast", "Slow")

    /** Experience needed to reach [level] for growth rate [growth] (0..5). */
    fun expForLevel(growth: Int, level: Int): Long {
        val n = level.toLong()
        if (level <= 1) return 0
        val cube = n * n * n
        val exp = when (growth) {
            1 -> when { // Erratic
                n <= 50 -> (100 - n) * cube / 50
                n <= 68 -> (150 - n) * cube / 100
                n <= 98 -> ((1911 - 10 * n) / 3) * cube / 500
                else -> (160 - n) * cube / 100
            }
            2 -> when { // Fluctuating
                n <= 15 -> ((n + 1) / 3 + 24) * cube / 50
                n <= 36 -> (n + 14) * cube / 50
                else -> ((n / 2) + 32) * cube / 50
            }
            3 -> 6 * cube / 5 - 15 * n * n + 100 * n - 140 // Medium Slow
            4 -> 4 * cube / 5 // Fast
            5 -> 5 * cube / 4 // Slow
            else -> cube // Medium Fast
        }
        return exp.coerceAtLeast(0)
    }

    fun levelForExp(growth: Int, exp: Long): Int {
        var level = 1
        while (level < MAX_LEVEL && expForLevel(growth, level + 1) <= exp) level++
        return level
    }

    /** +10% / -10% multipliers per stat for [nature], HP first (always 1.0). */
    fun natureMultiplierTenths(nature: Int, stat: Int): Int {
        if (stat == 0) return 10
        val up = nature / 5 + 1
        val down = nature % 5 + 1
        return when {
            up == down -> 10
            stat == up -> 11
            stat == down -> 9
            else -> 10
        }
    }

    /** Calculated stats in HP, Atk, Def, Spe, SpA, SpD order. */
    fun stats(base: BaseStats, level: Int, ivs: List<Int>, evs: List<Int>, nature: Int): List<Int> = List(6) { s ->
        val core = (2 * base.stats[s] + ivs[s] + evs[s] / 4) * level / 100
        if (s == 0) {
            if (base.stats[0] == 1) 1 else core + level + 10 // Shedinja always has 1 HP
        } else {
            (core + 5) * natureMultiplierTenths(nature, s) / 10
        }
    }

    fun gender(personality: Long, genderRatio: Int): Gender = when (genderRatio) {
        255 -> Gender.GENDERLESS
        254 -> Gender.FEMALE
        0 -> Gender.MALE
        else -> if ((personality and 0xFF).toInt() < genderRatio) Gender.FEMALE else Gender.MALE
    }

    fun isShiny(personality: Long, trainerId: Int, secretId: Int): Boolean =
        (trainerId xor secretId xor (personality shr 16).toInt() xor (personality and 0xFFFF).toInt()) < 8

    /**
     * Finds a personality value with the requested nature, gender and
     * shininess. (In Gen 3 these all derive from the personality value;
     * the ability slot is stored separately and isn't affected.)
     */
    fun findPersonality(
        nature: Int,
        gender: Gender,
        genderRatio: Int,
        shiny: Boolean,
        trainerId: Int,
        secretId: Int,
        random: Random = Random.Default,
    ): Long {
        require(nature in 0..24)
        repeat(1_000_000) {
            val low = random.nextInt(0x10000)
            val high = if (shiny) (low xor trainerId xor secretId xor random.nextInt(8)) and 0xFFFF else random.nextInt(0x10000)
            val pid = (high.toLong() shl 16) or low.toLong()
            if ((pid % 25).toInt() != nature) return@repeat
            if (gender(pid, genderRatio) != gender) return@repeat
            if (isShiny(pid, trainerId, secretId) != shiny) return@repeat
            return pid
        }
        throw IllegalArgumentException("No personality value has that combination (is the gender possible for this species?)")
    }
}
