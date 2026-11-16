package com.pokeemu.gen4

import com.pokeemu.core.LiveState

/**
 * Finds the player's and the opponent's party in a Gen 4 game's main RAM
 * (4 MB at 0x02000000) without per-game addresses, so hacks work too.
 *
 * Gen 4 keeps parties in RAM in the same shape as in the save: a u32 capacity
 * (6), a u32 count (1..6), then that many encrypted 236-byte party records.
 * The game holds several copies of the player's party (the save buffer, and
 * in battle the battle system's own); the opponent's party is a party whose
 * Pokémon are none of the player's.
 *
 * Which copy is live is decided by change: the copy whose bytes changed most
 * recently is shown, so HP drops in battle appear even while the save buffer
 * still holds the old values.
 */
class Gen4LiveReader(
    /** The player's trainer ID / secret ID from the save; null if unknown. */
    private val trainerId: Int?,
    private val secretId: Int?,
    private val speciesCount: Int = 2048,
    private val moveCount: Int = 1024,
) {
    private data class Party(val offset: Int, val mons: List<Pk4>, val bytes: Int) {
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
        fun lastChange(p: Party) = seen[p.offset]?.second ?: 0L

        // The player's party: the PID set held by the most copies, preferring ones with the player's IDs.
        val groups = parties.groupBy { it.pids }
        val playerPids = groups.keys.maxWithOrNull(
            compareBy<Set<Long>> { pids -> groups.getValue(pids).first().mons.count(::isPlayers) }
                .thenBy { pids -> groups.getValue(pids).size },
        ) ?: return null
        val player = groups.getValue(playerPids).maxWith(compareBy<Party> { lastChange(it) }.thenByDescending { it.offset })
        val opponent = parties
            .filter { it.pids.intersect(playerPids).isEmpty() }
            .maxWithOrNull(compareBy<Party> { lastChange(it) }.thenByDescending { it.offset })
        return LiveState(player.mons, opponent?.mons.orEmpty())
    }

    private fun isPlayers(pk: Pk4) =
        trainerId != null && pk.trainerId == trainerId && (secretId == null || pk.secretId == secretId)

    private fun findParties(ram: ByteArray): List<Party> {
        val out = mutableListOf<Party>()
        var at = 0
        while (at + 8 + Pk4.PARTY_SIZE <= ram.size) {
            // Cheap prefilter: capacity 6 then a count of 1..6.
            if (ram.u32(at) == 6L) {
                val count = ram.u32(at + 4).toInt()
                if (count in 1..6 && at + 8 + count * Pk4.PARTY_SIZE <= ram.size) {
                    val mons = (0 until count).mapNotNull { record(ram, at + 8 + it * Pk4.PARTY_SIZE) }
                    if (mons.size == count) out += Party(at + 8, mons, count * Pk4.PARTY_SIZE)
                }
            }
            at += 4
        }
        return out
    }

    /** A plausible decrypted party Pokémon at [at], or null. */
    fun record(ram: ByteArray, at: Int): Pk4? {
        if (at < 0 || at + Pk4.PARTY_SIZE > ram.size) return null
        val pk = Pk4(ram.copyOfRange(at, at + Pk4.PARTY_SIZE))
        if (!pk.checksumValid || pk.species !in 1 until speciesCount) return null
        val level = pk.level ?: return null
        val hp = pk.currentHp ?: return null
        val max = pk.maxHp ?: return null
        if (level !in 1..100 || max !in 1..MAX_STAT || hp > max) return null
        if (pk.partyStats!!.any { it !in 1..MAX_STAT }) return null
        if (pk.move(0) == 0 || pk.moves.any { it >= moveCount }) return null
        return pk
    }

    companion object {
        const val RAM_ADDRESS = 0x02000000L
        const val RAM_SIZE = 0x400000
        private const val MAX_STAT = 999
    }
}
