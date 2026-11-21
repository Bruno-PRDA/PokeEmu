package com.pokeemu.app

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameData
import com.pokeemu.core.LiveState
import com.pokeemu.core.Mon
import com.pokeemu.saves.ChallengeStore
import com.pokeemu.saves.GameStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChallengeRunnerTest {
    private class FakeMon(
        override val personality: Long,
        override var species: Int,
        override val metLocation: Int,
        override val metLevel: Int = 3,
        override val currentHp: Int? = 20,
        override val isShiny: Boolean = false,
    ) : Mon {
        override val nickname = ""
        override var heldItem = 0
        override var experience = 0L
        override var friendship = 0
        override val isEgg = false
        override val nature = 0
        override val trainerId = 1
        override val secretId = 2
        override val otName = "RED"
        override val checksumValid = true
        override val moves = listOf(33)
        override fun move(i: Int) = moves.getOrElse(i) { 0 }
        override fun pp(i: Int) = 0
        override fun setPp(i: Int, pp: Int) {}
        override fun ppUps(i: Int) = 0
        override fun setPpUps(i: Int, ups: Int) {}
        override fun iv(stat: Int) = 0
        override fun setIv(stat: Int, value: Int) {}
        override fun ev(stat: Int) = 0
        override fun setEv(stat: Int, value: Int) {}
        override val level: Int? = 5
        override val maxHp: Int? = 20
        override fun abilityId(base: BaseStats?): Int? = null
        override fun copy(): Mon = this
    }

    private val data = object : GameData {
        override val speciesNames = List(30) { when (it) { 1 -> "Bulbasaur"; 16 -> "Pidgey"; 19 -> "Rattata"; else -> "" } }
        override val moveNames = emptyList<String>()
        override val abilityNames = emptyList<String>()
        override val itemNames = emptyList<String>()
        override val report = emptyList<String>()
        override fun base(species: Int): BaseStats? = null
        override fun basePp(move: Int): Int? = null
    }

    @Test fun firstEncounterPerAreaCountsAndOthersAreFlagged() {
        val dir = Files.createTempDirectory("nuz").toFile()
        val store = ChallengeStore(GameStorage(dir), "rom", "main").apply { nuzlocke = true }
        val messages = mutableListOf<String>()
        val runner = ChallengeRunner(store, { data }, { _, _ -> true }, { messages += it }, places = { mapOf(89 to "ROUTE 1", 90 to "ROUTE 2") })
        val starter = FakeMon(1, 1, metLocation = 88, metLevel = 5)
        fun step(party: List<Mon>, foes: List<Mon> = emptyList()) = runner.onState(LiveState(party, foes, opponentsWild = foes.isNotEmpty()), ByteArray(0))

        step(listOf(starter))
        // First wild Pokémon on Route 1: that area's encounter.
        val pidgey = FakeMon(100, 16, metLocation = 89)
        step(listOf(starter), listOf(pidgey))
        assertTrue(messages.last().contains("first encounter in ROUTE 1"), messages.last())
        step(listOf(starter))
        // Caught: it joins the team, and the area is marked as caught.
        step(listOf(starter, pidgey))
        assertEquals(true, store.area(89)?.caught)
        assertEquals("ROUTE 1", store.area(89)?.place)
        // Another Route 1 Pokémon: flagged; catching it breaks the rules.
        val rattata = FakeMon(200, 19, metLocation = 89)
        step(listOf(starter, pidgey), listOf(rattata))
        assertTrue(messages.last().contains("already had its encounter"), messages.last())
        step(listOf(starter, pidgey))
        step(listOf(starter, pidgey, rattata))
        assertTrue(messages.last().contains("release"), messages.last())
        // A hatched Pokémon (met level 0) doesn't use up an area.
        step(listOf(starter, pidgey, rattata, FakeMon(300, 19, metLocation = 90, metLevel = 0)))
        assertEquals(null, store.area(90))
        dir.deleteRecursively()
    }

    @Test fun shinyAndDupesClausesDontUseUpAnArea() {
        val dir = Files.createTempDirectory("nuz2").toFile()
        val store = ChallengeStore(GameStorage(dir), "rom", "main").apply { nuzlocke = true }
        val messages = mutableListOf<String>()
        val runner = ChallengeRunner(store, { data }, { _, _ -> true }, { messages += it }, places = { mapOf(89 to "ROUTE 1") })
        val pidgey = FakeMon(1, 16, metLocation = 88, metLevel = 5)
        fun step(party: List<Mon>, foes: List<Mon> = emptyList()) = runner.onState(LiveState(party, foes, opponentsWild = foes.isNotEmpty()), ByteArray(0))
        step(listOf(pidgey))
        // A shiny: may be caught, the area stays free.
        step(listOf(pidgey), listOf(FakeMon(2, 19, metLocation = 89, isShiny = true)))
        assertTrue(messages.last().contains("shiny clause"), messages.last())
        assertEquals(null, store.area(89))
        step(listOf(pidgey))
        // Another Pidgey: the player has that family, so it doesn't count either.
        step(listOf(pidgey), listOf(FakeMon(3, 16, metLocation = 89)))
        assertTrue(messages.last().contains("dupes clause"), messages.last())
        assertEquals(null, store.area(89))
        step(listOf(pidgey))
        // A Rattata: the area's real encounter.
        step(listOf(pidgey), listOf(FakeMon(4, 19, metLocation = 89)))
        assertEquals(4L, store.area(89)?.pid)
        assertEquals(19, store.area(89)?.species)
        dir.deleteRecursively()
    }

}
