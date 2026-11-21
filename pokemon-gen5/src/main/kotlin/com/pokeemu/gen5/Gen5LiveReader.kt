package com.pokeemu.gen5

import com.pokeemu.core.LiveState

/**
 * Finds the player's and the opponent's party in a Black/White/Black 2/White 2
 * game's main RAM (4 MB at 0x02000000) without per-game addresses, so hacks
 * work too. Same approach as Gen 4: parties sit in RAM as a u32 capacity (6),
 * a u32 count (1..6), then that many encrypted 220-byte party records; the
 * player's party is the set of Pokémon most copies agree on (preferring ones
 * with the player's IDs), the opponent's is a party with none of them, and the
 * copy that changed last is shown.
 */
class Gen5LiveReader(
    /** The player's trainer ID / secret ID from the save; null if unknown. */
    private val trainerId: Int?,
    private val secretId: Int?,
    private val speciesCount: Int = 2048,
    private val moveCount: Int = 1024,
) {
    private data class Party(val offset: Int, val mons: List<Pk5>, val bytes: Int) {
        val pids get() = mons.map { it.personality }.toSet()
    }

    private var tick = 0L
    /** offset -> (content hash, tick it last changed) */
    private val seen = HashMap<Int, Pair<Int, Long>>()

    fun read(ram: ByteArray): LiveState? {
        tick++
        val parties = findParties(ram)
        if (parties.isEmpty()) return null
        for (p in parties) {
            val hash = ram.copyOfRange(p.offset, p.offset + p.bytes).contentHashCode()
            val prev = seen[p.offset]
            if (prev == null || prev.first != hash) seen[p.offset] = hash to (if (prev == null) 0L else tick)
        }
        if (seen.size > 512) seen.keys.retainAll(parties.map { it.offset }.toSet())
        fun lastChange(p: Party) = seen[p.offset]?.second ?: 0L

        val groups = parties.groupBy { it.pids }
        val playerPids = groups.keys.maxWithOrNull(
            compareBy<Set<Long>> { pids -> groups.getValue(pids).first().mons.count(::isPlayers) }
                .thenBy { pids -> groups.getValue(pids).size },
        ) ?: return null
        val player = groups.getValue(playerPids).maxWith(compareBy<Party> { lastChange(it) }.thenByDescending { it.offset })
        val opponent = parties
            .filter { it.pids.intersect(playerPids).isEmpty() && it.mons.none(::isPlayersOwn) }
            .maxWithOrNull(compareBy<Party> { lastChange(it) }.thenByDescending { it.offset })
        val foes = opponent?.mons.orEmpty()
        return LiveState(
            player.mons, foes,
            opponentOffsets = foes.indices.map { opponent!!.offset + it * Pk5.PARTY_SIZE },
            opponentsWild = foes.isNotEmpty() && if (trainerId != null) foes.all(::isPlayers) else
                player.mons.firstOrNull()?.let { lead -> foes.all { it.trainerId == lead.trainerId && it.secretId == lead.secretId } } == true,
        )
    }

    private fun isPlayers(pk: Pk5) =
        trainerId != null && pk.trainerId == trainerId && (secretId == null || pk.secretId == secretId)

    /** A Pokémon the player really owns (their IDs and a met date): a wild one only borrows the IDs. */
    private fun isPlayersOwn(pk: Pk5) = false

    private fun findParties(ram: ByteArray): List<Party> {
        val out = mutableListOf<Party>()
        var at = 0
        while (at + 8 + Pk5.PARTY_SIZE <= ram.size) {
            if (ram.u32(at) == 6L) {
                val count = ram.u32(at + 4).toInt()
                if (count in 1..6 && at + 8 + count * Pk5.PARTY_SIZE <= ram.size) {
                    val mons = (0 until count).mapNotNull { record(ram, at + 8 + it * Pk5.PARTY_SIZE) }
                    if (mons.size == count) out += Party(at + 8, mons, count * Pk5.PARTY_SIZE)
                }
            }
            at += 4
        }
        return out
    }

    /** A plausible decrypted party Pokémon at [at], or null. */
    fun record(ram: ByteArray, at: Int): Pk5? {
        if (at < 0 || at + Pk5.PARTY_SIZE > ram.size) return null
        val pk = Pk5(ram.copyOfRange(at, at + Pk5.PARTY_SIZE))
        if (!pk.checksumValid || pk.species !in 1 until speciesCount) return null
        val level = pk.level ?: return null
        val hp = pk.currentHp ?: return null
        val max = pk.maxHp ?: return null
        if (level !in 1..100 || max !in 1..MAX_STAT || hp > max) return null
        if (pk.partyStats!!.any { it !in 1..MAX_STAT }) return null
        if (pk.move(0) == 0 || pk.moves.any { it >= moveCount }) return null
        return pk
    }

    /** Every plausible party record in [ram] (for working out where a game keeps them). */
    fun scan(ram: ByteArray): List<Pair<Int, Pk5>> =
        (0 until ram.size - Pk5.PARTY_SIZE step 4).mapNotNull { at -> record(ram, at)?.let { at to it } }

    companion object {
        const val RAM_ADDRESS = 0x02000000L
        const val RAM_SIZE = 0x400000
        private const val MAX_STAT = 999
    }
}

/** This party record made shiny (same nature, gender and ability), re-encrypted. */
fun Pk5.shinyRecord(): ByteArray {
    val copy = Pk5(toBytes())
    copy.personality = com.pokeemu.core.ShinyPid.reroll(personality, trainerId, secretId)
    return copy.toBytes()
}
