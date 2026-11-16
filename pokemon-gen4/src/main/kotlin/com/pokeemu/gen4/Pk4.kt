package com.pokeemu.gen4

/**
 * One Gen 4 Pokémon: 136 bytes in the PC, 236 in the party.
 *
 * 0x08..0x87 hold four 32-byte blocks (A: species/item/IDs/EXP/EVs,
 * B: moves/IVs/flags, C: nickname, D: OT name/met data), shuffled by
 * ((pid >> 13) & 31) % 24 and XOR-encrypted with the Gen 4 LCG seeded by the
 * checksum. The party-only area 0x88..0xEB is encrypted with the LCG seeded
 * by the personality value. Decode → encode is byte-identical.
 */
class Pk4(raw: ByteArray) {
    init {
        require(raw.size == BOX_SIZE || raw.size == PARTY_SIZE) { "Pk4 must be 136 or 236 bytes, got ${raw.size}" }
    }

    private val bytes = raw.copyOf()
    /** Decrypted record in canonical A-B-C-D order (same offsets as [bytes]). */
    private val plain = ByteArray(raw.size)

    val isParty get() = bytes.size == PARTY_SIZE
    val checksumValid: Boolean

    init {
        bytes.copyInto(plain, 0, 0, 8)
        val lcg = Lcg(bytes.u16(6).toLong())
        val shuffled = ByteArray(128)
        for (w in 0 until 64) shuffled.put16(w * 2, bytes.u16(8 + w * 2) xor lcg.next())
        val order = ORDERS[shuffleValue()]
        for (pos in 0 until 4) shuffled.copyInto(plain, 8 + order[pos] * 32, pos * 32, pos * 32 + 32)
        if (isParty) {
            val partyLcg = Lcg(personality)
            for (w in 0 until 50) plain.put16(0x88 + w * 2, bytes.u16(0x88 + w * 2) xor partyLcg.next())
        }
        checksumValid = computeChecksum() == bytes.u16(6)
    }

    // From the plain header (identical to the raw header, which isn't encrypted).
    private fun shuffleValue() = (((plain.u32(0) shr 13) and 31) % 24).toInt()
    private fun computeChecksum(): Int {
        var sum = 0
        for (w in 0 until 64) sum += plain.u16(8 + w * 2)
        return sum and 0xFFFF
    }

    val personality: Long get() = plain.u32(0)
    val isEmpty: Boolean get() = species == 0 && personality == 0L

    var species: Int
        get() = plain.u16(0x08)
        set(v) = plain.put16(0x08, v)
    var heldItem: Int
        get() = plain.u16(0x0A)
        set(v) = plain.put16(0x0A, v)
    val trainerId: Int get() = plain.u16(0x0C)
    val secretId: Int get() = plain.u16(0x0E)
    var experience: Long
        get() = plain.u32(0x10)
        set(v) = plain.put32(0x10, v)
    var friendship: Int
        get() = plain.u8(0x14)
        set(v) { plain[0x14] = v.toByte() }
    val ability: Int get() = plain.u8(0x15)

    fun ev(stat: Int) = plain.u8(0x18 + stat)
    fun setEv(stat: Int, v: Int) { plain[0x18 + stat] = v.coerceIn(0, 255).toByte() }
    val evs get() = (0 until 6).map(::ev)

    fun move(i: Int) = plain.u16(0x28 + i * 2)
    fun setMove(i: Int, m: Int) = plain.put16(0x28 + i * 2, m)
    val moves get() = (0 until 4).map(::move)
    fun pp(i: Int) = plain.u8(0x30 + i)

    private var ivWord: Long
        get() = plain.u32(0x38)
        set(v) = plain.put32(0x38, v)
    fun iv(stat: Int) = ((ivWord shr (stat * 5)) and 0x1F).toInt()
    fun setIv(stat: Int, v: Int) {
        val s = stat * 5
        ivWord = (ivWord and (0x1FL shl s).inv()) or (v.coerceIn(0, 31).toLong() shl s)
    }
    val ivs get() = (0 until 6).map(::iv)
    val isEgg get() = (ivWord shr 30) and 1L == 1L
    val isNicknamed get() = (ivWord shr 31) and 1L == 1L

    val isFemale get() = plain.u8(0x40) and 2 != 0
    val isGenderless get() = plain.u8(0x40) and 4 != 0
    val form get() = plain.u8(0x40) shr 3

    val nickname: String get() = Gen4Text.decode(plain, 0x48, 11)
    val otName: String get() = Gen4Text.decode(plain, 0x68, 8)

    val nature get() = (personality % 25).toInt()
    val isShiny get() = (trainerId xor secretId xor (personality shr 16).toInt() xor (personality and 0xFFFF).toInt()) < 8

    val level: Int? get() = if (isParty) plain.u8(0x8C) else null
    val currentHp: Int? get() = if (isParty) plain.u16(0x8E) else null
    val maxHp: Int? get() = if (isParty) plain.u16(0x90) else null

    fun toBytes(): ByteArray {
        val out = plain.copyOf()
        val checksum = computeChecksum()
        out.put16(6, checksum)
        val order = ORDERS[shuffleValue()]
        val shuffled = ByteArray(128)
        for (pos in 0 until 4) plain.copyInto(shuffled, pos * 32, 8 + order[pos] * 32, 8 + order[pos] * 32 + 32)
        val lcg = Lcg(checksum.toLong())
        for (w in 0 until 64) out.put16(8 + w * 2, shuffled.u16(w * 2) xor lcg.next())
        if (isParty) {
            val partyLcg = Lcg(personality)
            for (w in 0 until 50) out.put16(0x88 + w * 2, plain.u16(0x88 + w * 2) xor partyLcg.next())
        }
        return out
    }

    companion object {
        const val BOX_SIZE = 136
        const val PARTY_SIZE = 236

        /** ORDERS[sv][position] = which block (0=A..3=D) is stored at that position. */
        private val ORDERS: Array<IntArray> = arrayOf(
            "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB",
            "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
            "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA",
            "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
        ).map { s -> IntArray(4) { s[it] - 'A' } }.toTypedArray()

        val NATURES = listOf(
            "Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed", "Impish", "Lax",
            "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet", "Bashful", "Rash",
            "Calm", "Gentle", "Sassy", "Careful", "Quirky",
        )

        /** Builds an encrypted record from canonical (decrypted) bytes; used by tests and future editing. */
        fun fromPlain(plainBytes: ByteArray): Pk4 {
            require(plainBytes.size == BOX_SIZE || plainBytes.size == PARTY_SIZE)
            // Encrypt by round-tripping through a record whose "plain" is our bytes.
            val tmp = Pk4(ByteArray(plainBytes.size))
            plainBytes.copyInto(tmp.plain)
            return Pk4(tmp.toBytes())
        }
    }
}
