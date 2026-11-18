package com.pokeemu.app

import com.pokeemu.core.GameData
import com.pokeemu.core.LiveState
import com.pokeemu.core.Mon
import com.pokeemu.core.ShinyPid
import com.pokeemu.gen3.Pk3
import com.pokeemu.gen4.Pk4
import com.pokeemu.gen5.Pk5
import com.pokeemu.saves.ChallengeStore
import kotlin.random.Random

/**
 * Runs a save's challenge modes on the live game, from RAM snapshots (the
 * same ones the battle overlay reads). Only the game's memory is changed:
 * - Shiny odds: each new wild Pokémon gets one roll; on a hit its personality
 *   value is rewritten to a shiny one (same nature, gender and ability),
 *   in every copy the game keeps of it.
 * - Nuzlocke: logs encounters and new team members, marks a Pokémon dead the
 *   first time its HP reaches 0, and warns when a dead one is in the party as a battle starts.
 *   Areas: a Pokémon's met location is the area it belongs to. The first wild
 *   Pokémon met in an area (when the game already notes where it is, as the GBA
 *   games do) or the first one caught there is that area's encounter; others
 *   met there are flagged as not to be caught, and a second catch as a rule break.
 */
class ChallengeRunner(
    private val store: ChallengeStore,
    private val data: () -> GameData?,
    /** Writes [bytes] at [offset] in the RAM region the snapshots come from. */
    private val write: (offset: Int, bytes: ByteArray) -> Boolean,
    private val notify: (String) -> Unit,
    /** Location number -> place name, read from the ROM (may be empty). */
    private val places: () -> Map<Int, String> = { emptyMap() },
) {
    private var warnedArea = -1
    private val rolled = HashSet<Long>()
    private var known: MutableSet<Long>? = null
    private var dead: MutableSet<Long> = store.dead().toMutableSet()
    private var inBattle = false
    @Volatile private var odds = store.shinyOdds
    @Volatile private var nuzlockeOn = store.nuzlocke

    /** True when any mode is on (otherwise the player skips reading memory for it). */
    val active get() = odds != 0 || nuzlockeOn

    /** Re-reads the settings after they change on the Challenges screen. */
    fun refresh() {
        odds = store.shinyOdds
        nuzlockeOn = store.nuzlocke
        dead = store.dead().toMutableSet()
    }

    private fun name(mon: Mon): String {
        val species = data()?.speciesName(mon.species) ?: "#${mon.species}"
        val nick = mon.nickname
        return if (nick.isBlank() || nick.equals(species, ignoreCase = true)) species else "$nick ($species)"
    }

    /** [ram] is the snapshot [state] was read from. */
    fun onState(state: LiveState, ram: ByteArray) {
        shinyOdds(state, ram)
        if (nuzlockeOn) nuzlocke(state)
        inBattle = state.opponents.isNotEmpty()
    }

    private fun shinyOdds(state: LiveState, ram: ByteArray) {
        val odds = odds
        if (odds == 0 || !state.opponentsWild) return
        state.opponents.forEachIndexed { i, mon ->
            if (!rolled.add(mon.personality)) return@forEachIndexed
            if (mon.isShiny || mon.isEgg) return@forEachIndexed
            if (odds > 1 && Random.nextInt(odds) != 0) return@forEachIndexed
            if (state.opponentOffsets.getOrNull(i) == null) return@forEachIndexed
            val old = mon.personality
            val new = ShinyPid.reroll(old, mon.trainerId, mon.secretId)
            val writes = when (mon) {
                is Pk3 -> ShinyPid.rewrites(ram, old, new, listOf(Pk3.PARTY_SIZE, Pk3.BOX_SIZE)) { b ->
                    Pk3(b, mon.encoding).takeIf { ShinyPid.sameMon(it, mon) }?.apply { personality = new }?.toBytes()
                }
                is Pk4 -> ShinyPid.rewrites(ram, old, new, listOf(Pk4.PARTY_SIZE, Pk4.BOX_SIZE)) { b ->
                    Pk4(b).takeIf { ShinyPid.sameMon(it, mon) }?.apply { personality = new }?.toBytes()
                }
                is Pk5 -> ShinyPid.rewrites(ram, old, new, listOf(Pk5.PARTY_SIZE, Pk5.BOX_SIZE)) { b ->
                    Pk5(b).takeIf { ShinyPid.sameMon(it, mon) }?.apply { personality = new }?.toBytes()
                }
                else -> return@forEachIndexed
            }
            if (writes.isNotEmpty() && writes.all { (at, bytes) -> write(at, bytes) }) {
                rolled += new
                notify("✨ It's a shiny ${data()?.speciesName(mon.species) ?: "Pokémon"}!")
            }
        }
        if (rolled.size > 5000) rolled.clear()
    }

    private fun place(location: Int) = places()[location]?.takeIf { it.isNotBlank() && it.any(Char::isLetter) } ?: "area #$location"

    /** Met locations that are real places (not trades, events, eggs or transfers). */
    private fun isArea(mon: Mon, location: Int) = location != 0 && when (mon) {
        is Pk3 -> location < 0xFD
        else -> location < 2000
    }

    /** A wild Pokémon met at [location] as a battle starts. */
    private fun met(foe: Mon, location: Int, wild: String) {
        val prev = store.area(location)
        if (prev == null) {
            store.putArea(ChallengeStore.Area(location, place(location), foe.personality, caught = false, text = wild))
            notify("Nuzlocke: first encounter in ${place(location)}. You may catch it.")
        } else if (prev.pid != foe.personality && warnedArea != location) {
            warnedArea = location
            notify("Nuzlocke: ${place(location)} already had its encounter (${prev.text}). Don't catch this one.")
        }
    }

    /** A Pokémon new to the team: its met location is the area it was caught in. */
    private fun caught(mon: Mon) {
        val loc = mon.metLocation
        if (!isArea(mon, loc) || mon.metLevel == 0) return // hatched, traded, gift from an event...
        val prev = store.area(loc)
        when {
            prev == null || prev.pid == mon.personality ->
                store.putArea(ChallengeStore.Area(loc, place(loc), mon.personality, caught = true, text = "Caught ${name(mon)}"))
            else -> notify("Nuzlocke: ${name(mon)} was caught in ${place(loc)}, but that area's encounter was ${prev.text}. The rules say release it.")
        }
    }

    private fun nuzlocke(state: LiveState) {
        val party = state.party
        // First snapshot: the team as it is, without logging it.
        val team = known ?: party.map { it.personality }.toMutableSet().also { known = it }
        for (mon in party) {
            if (mon.isEgg) continue
            if (team.add(mon.personality)) {
                store.add(ChallengeStore.Kind.JOINED, mon.personality, "${name(mon)} joined the team")
                caught(mon)
            }
            if (mon.currentHp == 0 && dead.add(mon.personality)) {
                store.add(ChallengeStore.Kind.DEATH, mon.personality, "${name(mon)} fainted")
                notify("💀 ${name(mon)} fainted. Nuzlocke rules: it can't be used any more.")
            }
        }
        val battleStarts = state.opponents.isNotEmpty() && !inBattle
        if (battleStarts) {
            if (state.opponentsWild) state.opponents.firstOrNull()?.let { foe ->
                val wild = "Wild ${data()?.speciesName(foe.species) ?: "#${foe.species}"} Lv ${foe.level ?: "?"}"
                val loc = foe.metLocation.takeIf { isArea(foe, it) }
                store.add(ChallengeStore.Kind.ENCOUNTER, foe.personality, if (loc != null) "$wild in ${place(loc)}" else wild)
                if (loc != null) met(foe, loc, wild)
            }
            val stillThere = party.filter { it.personality in dead }
            if (stillThere.isNotEmpty()) notify("Nuzlocke: ${stillThere.joinToString { name(it) }} fainted earlier and shouldn't battle.")
        }
    }
}
