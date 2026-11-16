package com.pokeemu.gen4

import com.pokeemu.core.GameData
import com.pokeemu.core.Mon

/**
 * One Gen 4 Pokémon: 136 bytes in the PC, 236 in the party.
 *
 * 0x08..0x87 hold four 32-byte blocks (A: species/item/IDs/EXP/EVs,
 * B: moves/IVs/flags, C: nickname, D: OT name/met data), shuffled by
 * ((pid >> 13) & 31) % 24 and XOR-encrypted with the Gen 4 LCG seeded by the
 * checksum. The party-only area 0x88..0xEB is encrypted with the LCG seeded
 * by the personality value. Decode → encode is byte-identical.
 *
 * Fields are edited on the decrypted copy; [toBytes] re-shuffles and
 * re-encrypts with the current personality value and checksum.
 */
class Pk4(raw: ByteArray) : Mon {
    init {
        require(raw.size == BOX_SIZE || raw.size == PARTY_SIZE) { "Pk4 must be 136 or 236 bytes, got ${raw.size}" }
    }

    /** Decrypted record in canonical A-B-C-D order. */
    private val plain = ByteArray(raw.size)

    val isParty get() = plain.size == PARTY_SIZE
    override val checksumValid: Boolean

    init {
        raw.copyInto(plain, 0, 0, 8)
        val lcg = Lcg(raw.u16(6).toLong())
        val shuffled = ByteArray(128)
        for (w in 0 until 64) shuffled.put16(w * 2, raw.u16(8 + w * 2) xor lcg.next())
        val order = ORDERS[shuffleValue()]
        for (pos in 0 until 4) shuffled.copyInto(plain, 8 + order[pos] * 32, pos * 32, pos * 32 + 32)
        if (isParty) {
            val partyLcg = Lcg(personality)
            for (w in 0 until 50) plain.put16(0x88 + w * 2, raw.u16(0x88 + w * 2) xor partyLcg.next())
        }
        checksumValid = computeChecksum() == raw.u16(6)
    }

    private fun shuffleValue() = (((plain.u32(0) shr 13) and 31) % 24).toInt()
    private fun computeChecksum(): Int {
        var sum = 0
        for (w in 0 until 64) sum += plain.u16(8 + w * 2)
        return sum and 0xFFFF
    }

    override var personality: Long
        get() = plain.u32(0)
        set(v) = plain.put32(0, v and 0xFFFFFFFFL)
    val isEmpty: Boolean get() = species == 0 && personality == 0L

    // ---- block A ----
    override var species: Int
        get() = plain.u16(0x08)
        set(v) = plain.put16(0x08, v)
    override var heldItem: Int
        get() = plain.u16(0x0A)
        set(v) = plain.put16(0x0A, v)
    override val trainerId: Int get() = plain.u16(0x0C)
    override val secretId: Int get() = plain.u16(0x0E)
    override var experience: Long
        get() = plain.u32(0x10)
        set(v) = plain.put32(0x10, v)
    override var friendship: Int
        get() = plain.u8(0x14)
        set(v) { plain[0x14] = v.coerceIn(0, 255).toByte() }
    /** Ability id itself (not a slot) — Gen 4 stores it directly. */
    var ability: Int
        get() = plain.u8(0x15)
        set(v) { plain[0x15] = v.toByte() }

    override fun ev(stat: Int) = plain.u8(0x18 + stat)
    override fun setEv(stat: Int, value: Int) { plain[0x18 + stat] = value.coerceIn(0, 255).toByte() }

    // ---- block B ----
    override fun move(i: Int) = plain.u16(0x28 + i * 2)
    fun setMove(i: Int, m: Int) = plain.put16(0x28 + i * 2, m)
    override val moves get() = (0 until 4).map(::move)
    override fun pp(i: Int) = plain.u8(0x30 + i)
    override fun setPp(i: Int, pp: Int) { plain[0x30 + i] = pp.coerceIn(0, 255).toByte() }
    override fun ppUps(i: Int) = plain.u8(0x34 + i)
    override fun setPpUps(i: Int, ups: Int) { plain[0x34 + i] = ups.coerceIn(0, 3).toByte() }

    private var ivWord: Long
        get() = plain.u32(0x38)
        set(v) = plain.put32(0x38, v)
    override fun iv(stat: Int) = ((ivWord shr (stat * 5)) and 0x1F).toInt()
    override fun setIv(stat: Int, value: Int) {
        val s = stat * 5
        ivWord = (ivWord and (0x1FL shl s).inv()) or (value.coerceIn(0, 31).toLong() shl s)
    }
    override val isEgg get() = (ivWord shr 30) and 1L == 1L
    var isNicknamed: Boolean
        get() = (ivWord shr 31) and 1L == 1L
        set(v) { ivWord = if (v) ivWord or (1L shl 31) else ivWord and (1L shl 31).inv() }

    /** Byte 0x40: bit 0 fateful encounter, bit 1 female, bit 2 genderless, bits 3-7 form. */
    var isFemale: Boolean
        get() = plain.u8(0x40) and 2 != 0
        set(v) { plain[0x40] = ((plain.u8(0x40) and 2.inv()) or (if (v) 2 else 0)).toByte() }
    var isGenderless: Boolean
        get() = plain.u8(0x40) and 4 != 0
        set(v) { plain[0x40] = ((plain.u8(0x40) and 4.inv()) or (if (v) 4 else 0)).toByte() }
    val form get() = plain.u8(0x40) shr 3

    // ---- blocks C, D ----
    override val nickname: String get() = Gen4Text.decode(plain, 0x48, 11)
    fun setNickname(text: String) = Gen4Text.encode(text, 11).copyInto(plain, 0x48)
    override val otName: String get() = Gen4Text.decode(plain, 0x68, 8)

    override val nature get() = (personality % 25).toInt()
    override val isShiny get() = (trainerId xor secretId xor (personality shr 16).toInt() xor (personality and 0xFFFF).toInt()) < 8

    // ---- party-only ----
    val level: Int? get() = if (isParty) plain.u8(0x8C) else null
    val currentHp: Int? get() = if (isParty) plain.u16(0x8E) else null
    val maxHp: Int? get() = if (isParty) plain.u16(0x90) else null
    val partyStats: List<Int>? get() = if (isParty) List(6) { plain.u16(0x90 + it * 2) } else null

    /** Writes level and stats (HP, Atk, Def, Spe, SpA, SpD), keeping HP lost to damage. */
    fun setPartyStats(level: Int, stats: List<Int>) {
        require(isParty)
        val oldMax = plain.u16(0x90)
        val oldHp = plain.u16(0x8E)
        val newMax = stats[0]
        val hp = when {
            oldMax == 0 -> newMax
            oldHp == 0 -> 0
            else -> (newMax - (oldMax - oldHp)).coerceIn(1, newMax)
        }
        plain[0x8C] = level.toByte()
        plain.put16(0x8E, hp)
        stats.forEachIndexed { i, v -> plain.put16(0x90 + i * 2, v) }
    }

    override fun copy(): Pk4 = Pk4(toBytes())

    /** Party record with an empty party area (status cleared); stats are filled in by the editor. */
    fun toPartyForm(): Pk4 = if (isParty) copy() else fromPlain(plain.copyOf(PARTY_SIZE))
    /** The box part is encrypted independently of the party area, so truncation is valid. */
    fun toBoxForm(): Pk4 = Pk4(toBytes().copyOf(BOX_SIZE))

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

        val NATURES get() = GameData.NATURES

        /** Builds an encrypted record from canonical (decrypted) bytes. */
        fun fromPlain(plainBytes: ByteArray): Pk4 {
            require(plainBytes.size == BOX_SIZE || plainBytes.size == PARTY_SIZE)
            val tmp = Pk4(ByteArray(plainBytes.size))
            plainBytes.copyInto(tmp.plain)
            return Pk4(tmp.toBytes())
        }
    }
}
