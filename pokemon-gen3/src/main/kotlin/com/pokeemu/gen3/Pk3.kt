package com.pokeemu.gen3

/**
 * One Gen 3 Pokémon: 80 bytes in the PC, 100 bytes in the party (adds level,
 * HP and calculated stats).
 *
 * Bytes 0x20..0x4F hold four 12-byte substructures — Growth, Attacks,
 * EVs/Condition, Misc — shuffled into one of 24 orders (personality % 24)
 * and XOR-encrypted with (personality xor original-trainer id). This class
 * decrypts on construction and re-encrypts in [toBytes]; unknown bytes are
 * preserved, so decode → encode is byte-identical.
 */
class Pk3(raw: ByteArray) {
    init {
        require(raw.size == BOX_SIZE || raw.size == PARTY_SIZE) { "Pk3 must be 80 or 100 bytes, got ${raw.size}" }
    }

    private val bytes = raw.copyOf()
    /** Decrypted substructures in canonical G, A, E, M order. */
    private val data = ByteArray(48)

    val isParty: Boolean get() = bytes.size == PARTY_SIZE

    /** True if the stored checksum matched the decrypted data (false = "Bad Egg"). */
    val checksumValid: Boolean

    init {
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

    // ---- unencrypted header -------------------------------------------------------

    val personality: Long get() = bytes.u32(0x00)
    val otId: Long get() = bytes.u32(0x04)
    val trainerId: Int get() = (otId and 0xFFFF).toInt()
    val secretId: Int get() = (otId shr 16).toInt()

    var nickname: String
        get() = Gen3Text.decode(bytes, 0x08, 10)
        set(v) {
            Gen3Text.encode(v, 10).copyInto(bytes, 0x08)
        }

    val otName: String get() = Gen3Text.decode(bytes, 0x14, 7)
    val language: Int get() = bytes.u8(0x12)

    /** An all-zero slot (species 0, no personality). */
    val isEmpty: Boolean get() = species == 0 && personality == 0L && otId == 0L

    // ---- Growth (substructure 0) ------------------------------------------------------

    /** Internal species index (not National Dex number). */
    var species: Int
        get() = data.u16(0)
        set(v) = data.put16(0, v)
    var heldItem: Int
        get() = data.u16(2)
        set(v) = data.put16(2, v)
    var experience: Long
        get() = data.u32(4)
        set(v) = data.put32(4, v)
    var ppBonuses: Int
        get() = data.u8(8)
        set(v) = data.put8(8, v)
    var friendship: Int
        get() = data.u8(9)
        set(v) = data.put8(9, v)

    // ---- Attacks (substructure 1) -----------------------------------------------------

    fun move(i: Int): Int = data.u16(12 + i * 2)
    fun setMove(i: Int, move: Int) = data.put16(12 + i * 2, move)
    fun pp(i: Int): Int = data.u8(20 + i)
    fun setPp(i: Int, pp: Int) = data.put8(20 + i, pp)
    val moves: List<Int> get() = (0 until 4).map(::move)

    // ---- EVs & condition (substructure 2) ----------------------------------------------

    /** EVs in HP, Atk, Def, Spe, SpA, SpD order. */
    fun ev(stat: Int): Int = data.u8(24 + stat)
    fun setEv(stat: Int, value: Int) = data.put8(24 + stat, value.coerceIn(0, 255))
    val evs: List<Int> get() = (0 until 6).map(::ev)

    // ---- Misc (substructure 3) ---------------------------------------------------------

    val metLocation: Int get() = data.u8(37)
    private var ivWord: Long
        get() = data.u32(40)
        set(v) = data.put32(40, v)

    /** IVs in HP, Atk, Def, Spe, SpA, SpD order. */
    fun iv(stat: Int): Int = ((ivWord shr (stat * 5)) and 0x1F).toInt()
    fun setIv(stat: Int, value: Int) {
        val shift = stat * 5
        ivWord = (ivWord and (0x1FL shl shift).inv()) or (value.coerceIn(0, 31).toLong() shl shift)
    }
    val ivs: List<Int> get() = (0 until 6).map(::iv)

    var isEgg: Boolean
        get() = (ivWord shr 30) and 1L == 1L
        set(v) { ivWord = if (v) ivWord or (1L shl 30) else ivWord and (1L shl 30).inv() }

    /** 0 = first ability, 1 = second ability. */
    var abilitySlot: Int
        get() = ((ivWord shr 31) and 1L).toInt()
        set(v) { ivWord = if (v == 1) ivWord or (1L shl 31) else ivWord and (1L shl 31).inv() }

    // ---- derived -------------------------------------------------------------------------

    /** Nature index 0..24 (Hardy, Lonely, Brave, ...). */
    val nature: Int get() = (personality % 25).toInt()

    val isShiny: Boolean
        get() = (trainerId xor secretId xor (personality shr 16).toInt() xor (personality and 0xFFFF).toInt()) < 8

    // ---- party-only fields ---------------------------------------------------------------

    val level: Int? get() = if (isParty) bytes.u8(0x54) else null
    val currentHp: Int? get() = if (isParty) bytes.u16(0x56) else null
    val maxHp: Int? get() = if (isParty) bytes.u16(0x58) else null

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
