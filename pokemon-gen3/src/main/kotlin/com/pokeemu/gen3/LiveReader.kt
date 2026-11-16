package com.pokeemu.gen3

/** What the overlay shows: your party and the opponent's, straight from game RAM. */
data class LiveState(val party: List<Pk3>, val opponents: List<Pk3>)

/**
 * Finds the player's and the opponent's party in a Gen 3 game's EWRAM without
 * per-game addresses, so it works for official games and hacks alike.
 *
 * - Party records in RAM are the 100-byte party format with valid data
 *   (checksum, or the plain format some hacks use), level 1..100 and HP <= max.
 * - The player's party is the run of such records with the player's trainer
 *   ID. The game also keeps a copy inside the save buffer, preceded by the
 *   party count as a u32 — that copy is skipped.
 * - The opponent's party sits right next to the player's: 600 bytes before it
 *   (FireRed/LeafGreen) or after it (Ruby/Sapphire/Emerald). Wild Pokémon
 *   carry the player's trainer ID, so when two runs sit 600 bytes apart the
 *   game family decides which one is the player's.
 *
 * Offsets are cached and re-validated on every read, and searched again if the
 * game moves things around.
 */
class LiveReader(
    /** Full 32-bit OT id (TID | SID << 16) of the player, from the save; null if unknown. */
    private val playerOtId: Long?,
    game: Gen3Game,
    /**
     * Record format used by this game (from the save). Null = unknown: only the
     * standard format is accepted, because zero-checksum "plain" records are too
     * easy to match by chance in random memory.
     */
    private val encoding: Pk3Encoding? = null,
    /** Exclusive upper bounds from the ROM's tables, to reject impossible records. */
    private val speciesCount: Int = 2048,
    private val moveCount: Int = 1024,
) {
    /** Where the opponent's party is relative to the player's. */
    private val opponentDelta = if (game == Gen3Game.FIRERED_LEAFGREEN) -PARTY_BYTES else PARTY_BYTES
    private var partyOffset: Int? = null

    fun read(ram: ByteArray): LiveState? {
        val offset = partyOffset?.takeIf { record(ram, it) != null } ?: locate(ram).also { partyOffset = it } ?: return null
        return LiveState(run(ram, offset), run(ram, offset + opponentDelta))
    }

    /** Offset of the live player party in [ram], or null. */
    fun locate(ram: ByteArray): Int? {
        val candidates = mutableListOf<Int>()
        var i = 0
        while (i + Pk3.PARTY_SIZE <= ram.size) {
            val pk = record(ram, i)
            if (pk != null && (playerOtId == null || pk.otId == playerOtId) && record(ram, i - Pk3.PARTY_SIZE) == null) {
                candidates += i
            }
            i += 4
        }
        // Skip the save-buffer copy: it's preceded by the party count (u32).
        val live = candidates.filterNot { c -> c >= 4 && ram.u32(c - 4) == run(ram, c).size.toLong() }.ifEmpty { candidates }
        // In battle the opponent's run (wild Pokémon share the player's ID) is a
        // candidate too; the player's is the one with the opponent slot beside it.
        return live.firstOrNull { c -> (c - opponentDelta) !in live } ?: live.firstOrNull()
    }

    private fun run(ram: ByteArray, start: Int): List<Pk3> =
        (0 until 6).map { start + it * Pk3.PARTY_SIZE }.takeWhile { record(ram, it) != null }.map { record(ram, it)!! }

    /** A plausible in-RAM party Pokémon at [at], or null. */
    fun record(ram: ByteArray, at: Int): Pk3? {
        if (at < 0 || at + Pk3.PARTY_SIZE > ram.size) return null
        // Cheap checks straight on RAM first: this runs at every offset while searching.
        val level = ram.u8(at + 0x54)
        val hp = ram.u16(at + 0x56)
        val maxHp = ram.u16(at + 0x58)
        if (level !in 1..100 || maxHp !in 1..MAX_STAT || hp > maxHp) return null
        for (s in 1 until 6) if (ram.u16(at + 0x58 + s * 2) !in 1..MAX_STAT) return null
        if (ram.u32(at) == 0L && ram.u32(at + 4) == 0L) return null // empty slot
        val raw = ram.copyOfRange(at, at + Pk3.PARTY_SIZE)
        val candidates = when (encoding) {
            null, Pk3Encoding.STANDARD -> listOf(Pk3(raw))
            Pk3Encoding.PLAIN -> if (raw.u16(0x1C) == 0) listOf(Pk3(raw, Pk3Encoding.PLAIN)) else emptyList()
        }
        return candidates.firstOrNull { pk -> pk.checksumValid && plausible(pk) }
    }

    private fun plausible(pk: Pk3): Boolean =
        pk.species in 1 until speciesCount &&
            pk.experience <= MAX_EXP &&
            pk.move(0) != 0 &&
            pk.moves.all { it < moveCount }

    companion object {
        private const val PARTY_BYTES = 6 * Pk3.PARTY_SIZE
        /** Gen 3 maxima are 714 HP / 600-ish stats; 999 leaves room for hacks. */
        private const val MAX_STAT = 999
        private const val MAX_EXP = 2_000_000L
    }
}
