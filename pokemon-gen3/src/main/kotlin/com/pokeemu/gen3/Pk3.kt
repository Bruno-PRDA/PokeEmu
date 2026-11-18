package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.Mon

/** How the 48 data bytes of a Pokémon record are stored. */
enum class Pk3Encoding {
    /** Vanilla: shuffled by personality, XOR-encrypted, with a checksum. */
    STANDARD,
    /** Some hack engines (e.g. CFRU builds like Pokémon Odyssey): plain G-A-E-M order, checksum left at 0. */
    PLAIN,
}

/**
 * One Gen 3 Pokémon: 80 bytes in the PC, 100 bytes in the party (adds level,
 * HP and calculated stats).
 *
 * Bytes 0x20..0x4F hold four 12-byte substructures — Growth, Attacks,
 * EVs/Condition, Misc. With [Pk3Encoding.STANDARD] they're shuffled into one
 * of 24 orders (personality % 24) and XOR-encrypted with (personality xor
 * original-trainer id). This class decodes on construction and re-encodes in
 * [toBytes]; unknown bytes are preserved, so decode → encode is byte-identical.
 */
class Pk3(raw: ByteArray, val encoding: Pk3Encoding = Pk3Encoding.STANDARD) : Mon {
    init {
        require(raw.size == BOX_SIZE || raw.size == PARTY_SIZE) { "Pk3 must be 80 or 100 bytes, got ${raw.size}" }
    }

    private val bytes = raw.copyOf()
    /** Decrypted substructures in canonical G, A, E, M order. */
    private val data = ByteArray(48)

    val isParty: Boolean get() = bytes.size == PARTY_SIZE

    /** True if the stored checksum matched the decrypted data (false = "Bad Egg"). */
    override val checksumValid: Boolean

    init {
        if (encoding == Pk3Encoding.PLAIN) {
            bytes.copyInto(data, 0, 0x20, 0x50)
            checksumValid = true // this format has no checksum
        } else {
            val key = key()
            val order = ORDERS[(personality % 24).toInt()]
            for (pos in 0 until 4) {
                val sub = order[pos]
                for (w in 0 until 3) {
                    val src = 0x20 + pos * 12 + w * 4
                    data.put32(sub * 12 + w * 4, bytes.u32(src) xor key)
                }
            }
            checksumValid = computeChecksum() == bytes.u16(0x1C)
        }
    }

    /** Independent copy (same format and encoding). */
    override fun copy(): Pk3 = Pk3(toBytes(), encoding)

    /** Same Pokémon stored with another encoding (e.g. standard for .pk3 export). */
    fun withEncoding(target: Pk3Encoding): Pk3 {
        if (target == encoding) return copy()
        val out = Pk3(ByteArray(bytes.size), target)
        bytes.copyInto(out.bytes)
        data.copyInto(out.data)
        return Pk3(out.toBytes(), target)
    }

    // ---- unencrypted header -------------------------------------------------------

    /**
     * Changing this changes the encryption key and substructure order; that is
     * handled in [toBytes] because the decrypted data is kept separately.
     */
    override var personality: Long
        get() = bytes.u32(0x00)
        set(v) = bytes.put32(0x00, v and 0xFFFFFFFFL)
    val otId: Long get() = bytes.u32(0x04)
    override val trainerId: Int get() = (otId and 0xFFFF).toInt()
    override val secretId: Int get() = (otId shr 16).toInt()

    override var nickname: String
        get() = Gen3Text.decode(bytes, 0x08, 10)
        set(v) {
            Gen3Text.encode(v, 10).copyInto(bytes, 0x08)
        }

    override val otName: String get() = Gen3Text.decode(bytes, 0x14, 7)
    val language: Int get() = bytes.u8(0x12)

    /** An all-zero slot (species 0, no personality). */
    val isEmpty: Boolean get() = species == 0 && personality == 0L && otId == 0L

    // ---- Growth (substructure 0) ------------------------------------------------------

    /** Internal species index (not National Dex number). */
    override var species: Int
        get() = data.u16(0)
        set(v) = data.put16(0, v)
    override var heldItem: Int
        get() = data.u16(2)
        set(v) = data.put16(2, v)
    override var experience: Long
        get() = data.u32(4)
        set(v) = data.put32(4, v)
    var ppBonuses: Int
        get() = data.u8(8)
        set(v) = data.put8(8, v)
    override var friendship: Int
        get() = data.u8(9)
        set(v) = data.put8(9, v)

    // ---- Attacks (substructure 1) -----------------------------------------------------

    override fun move(i: Int): Int = data.u16(12 + i * 2)
    fun setMove(i: Int, move: Int) = data.put16(12 + i * 2, move)
    override fun pp(i: Int): Int = data.u8(20 + i)
    override fun setPp(i: Int, pp: Int) = data.put8(20 + i, pp)
    override val moves: List<Int> get() = (0 until 4).map(::move)
    override fun ppUps(i: Int): Int = (ppBonuses shr (i * 2)) and 3
    override fun setPpUps(i: Int, ups: Int) {
        ppBonuses = (ppBonuses and (3 shl (i * 2)).inv()) or ((ups and 3) shl (i * 2))
    }

    // ---- EVs & condition (substructure 2) ----------------------------------------------

    /** EVs in HP, Atk, Def, Spe, SpA, SpD order. */
    override fun ev(stat: Int): Int = data.u8(24 + stat)
    override fun setEv(stat: Int, value: Int) = data.put8(24 + stat, value.coerceIn(0, 255))
    override val evs: List<Int> get() = (0 until 6).map(::ev)

    // ---- Misc (substructure 3) ---------------------------------------------------------

    override val metLocation: Int get() = data.u8(37)
    /** Origins: met level (bits 0-6), game (7-10), ball (11-14), OT is female (15). */
    val origins: Int get() = data.u16(38)
    override val metLevel: Int get() = origins and 0x7F
    val originGame: Int get() = (origins shr 7) and 0xF
    override val originVersion: Int get() = originGame
    val ball: Int get() = (origins shr 11) and 0xF
    val otFemale: Boolean get() = (origins shr 15) and 1 == 1
    private var ivWord: Long
        get() = data.u32(40)
        set(v) = data.put32(40, v)

    /** IVs in HP, Atk, Def, Spe, SpA, SpD order. */
    override fun iv(stat: Int): Int = ((ivWord shr (stat * 5)) and 0x1F).toInt()
    override fun setIv(stat: Int, value: Int) {
        val shift = stat * 5
        ivWord = (ivWord and (0x1FL shl shift).inv()) or (value.coerceIn(0, 31).toLong() shl shift)
    }
    override val ivs: List<Int> get() = (0 until 6).map(::iv)

    override var isEgg: Boolean
        get() = (ivWord shr 30) and 1L == 1L
        set(v) { ivWord = if (v) ivWord or (1L shl 30) else ivWord and (1L shl 30).inv() }

    /** 0 = first ability, 1 = second ability. */
    var abilitySlot: Int
        get() = ((ivWord shr 31) and 1L).toInt()
        set(v) { ivWord = if (v == 1) ivWord or (1L shl 31) else ivWord and (1L shl 31).inv() }

    // ---- derived -------------------------------------------------------------------------

    /** Nature index 0..24 (Hardy, Lonely, Brave, ...). */
    override val nature: Int get() = (personality % 25).toInt()

    override val isShiny: Boolean
        get() = (trainerId xor secretId xor (personality shr 16).toInt() xor (personality and 0xFFFF).toInt()) < 8

    // ---- party-only fields ---------------------------------------------------------------

    override val level: Int? get() = if (isParty) bytes.u8(0x54) else null
    override val currentHp: Int? get() = if (isParty) bytes.u16(0x56) else null
    override val maxHp: Int? get() = if (isParty) bytes.u16(0x58) else null

    override fun abilityId(base: BaseStats?): Int? = base?.let { if (abilitySlot == 1 && it.ability2 != 0) it.ability2 else it.ability1 }
    /** Party stats in HP, Atk, Def, Spe, SpA, SpD order. */
    val partyStats: List<Int>? get() = if (isParty) List(6) { bytes.u16(0x58 + it * 2) } else null

    /**
     * Updates the party-only fields after an edit. Current HP keeps the same
     * damage taken (never below 1 unless already fainted, never above max).
     */
    fun setPartyStats(level: Int, stats: List<Int>) {
        check(isParty) { "box Pokémon have no stored stats" }
        val oldMax = bytes.u16(0x58)
        val oldHp = bytes.u16(0x56)
        val newMax = stats[0]
        val hp = when {
            oldMax == 0 -> newMax // freshly created / withdrawn: full health
            oldHp == 0 -> 0 // stays fainted
            else -> (newMax - (oldMax - oldHp)).coerceIn(1, newMax)
        }
        bytes.put8(0x54, level)
        bytes.put16(0x56, hp)
        stats.forEachIndexed { i, v -> bytes.put16(0x58 + i * 2, v) }
    }

    /** 100-byte copy with empty party fields (call [setPartyStats] afterwards). */
    fun toPartyForm(): Pk3 = if (isParty) copy() else Pk3(toBytes().copyOf(PARTY_SIZE), encoding)

    /** 80-byte PC form. */
    fun toBoxForm(): Pk3 = Pk3(toBoxBytes(), encoding)

    // ---- encoding ------------------------------------------------------------------------

    private fun key(): Long = personality xor otId

    private fun computeChecksum(): Int {
        var sum = 0
        for (i in 0 until 48 step 2) sum += data.u16(i)
        return sum and 0xFFFF
    }

    /** Re-shuffles, re-encrypts and re-checksums. Same length as the input. */
    fun toBytes(): ByteArray {
        val out = bytes.copyOf()
        if (encoding == Pk3Encoding.PLAIN) {
            out.put16(0x1C, 0) // mirror the game: no checksum in this format
            data.copyInto(out, 0x20)
            return out
        }
        out.put16(0x1C, computeChecksum())
        val key = key()
        val order = ORDERS[(personality % 24).toInt()]
        for (pos in 0 until 4) {
            val sub = order[pos]
            for (w in 0 until 3) {
                out.put32(0x20 + pos * 12 + w * 4, data.u32(sub * 12 + w * 4) xor key)
            }
        }
        return out
    }

    /** Box (80-byte) form of this Pokémon. */
    fun toBoxBytes(): ByteArray = toBytes().copyOf(BOX_SIZE)

    companion object {
        const val BOX_SIZE = 80
        const val PARTY_SIZE = 100

        /** A blank Pokémon (all data zero) with the given identity; set fields, then [toBytes]. */
        fun create(personality: Long, otId: Long, party: Boolean = true): Pk3 {
            val raw = ByteArray(if (party) PARTY_SIZE else BOX_SIZE)
            raw.put32(0x00, personality)
            raw.put32(0x04, otId)
            // Encrypted zero data is just the key repeated, so it decrypts to zeros.
            val key = personality xor otId
            for (w in 0 until 12) raw.put32(0x20 + w * 4, key)
            return Pk3(raw)
        }

        val NATURES = listOf(
            "Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed", "Impish", "Lax",
            "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet", "Bashful", "Rash",
            "Calm", "Gentle", "Sassy", "Careful", "Quirky",
        )

        /**
         * ORDERS[personality % 24][position] = which substructure (0=G, 1=A, 2=E, 3=M)
         * is stored at that position.
         */
        private val ORDERS: Array<IntArray> = arrayOf(
            "GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA",
            "AGEM", "AGME", "AEGM", "AEMG", "AMGE", "AMEG",
            "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG",
            "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG",
        ).map { s -> IntArray(4) { "GAEM".indexOf(s[it]) } }.toTypedArray()
    }
}
