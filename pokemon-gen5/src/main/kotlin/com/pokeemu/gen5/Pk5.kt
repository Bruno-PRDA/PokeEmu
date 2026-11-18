package com.pokeemu.gen5

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameData
import com.pokeemu.core.Mon

/**
 * One Gen 5 Pokémon: 136 bytes in the PC, 220 in the party.
 *
 * Encrypted like Gen 4 (four 32-byte blocks shuffled by ((pid >> 13) & 31) % 24,
 * XOR with the LCG seeded by the checksum; the party area 0x88..0xDB with the LCG
 * seeded by the personality value). Differences from Gen 4: names are UTF-16, the
 * nature has its own byte (0x41), and 0x42 bit 0 marks the hidden ability.
 */
class Pk5(raw: ByteArray) : Mon {
    init {
        require(raw.size == BOX_SIZE || raw.size == PARTY_SIZE) { "Pk5 must be 136 or 220 bytes, got ${raw.size}" }
    }

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
            for (w in 0 until PARTY_WORDS) plain.put16(0x88 + w * 2, raw.u16(0x88 + w * 2) xor partyLcg.next())
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
    /** The ability id itself. */
    var ability: Int
        get() = plain.u8(0x15)
        set(v) { plain[0x15] = v.toByte() }

    override fun ev(stat: Int) = plain.u8(0x18 + stat)
    override fun setEv(stat: Int, value: Int) { plain[0x18 + stat] = value.coerceIn(0, 255).toByte() }

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

    /** Gen 5 stores the nature itself (it no longer follows from the personality value). */
    override val nature: Int get() = plain.u8(0x41).takeIf { it < 25 } ?: (personality % 25).toInt()
    fun setNature(n: Int) { plain[0x41] = n.coerceIn(0, 24).toByte() }
    var hasHiddenAbility: Boolean
        get() = plain.u8(0x42) and 1 != 0
        set(v) { plain[0x42] = ((plain.u8(0x42) and 1.inv()) or (if (v) 1 else 0)).toByte() }

    override val nickname: String get() = decodeString(plain, 0x48, 11)
    fun setNickname(text: String) = encodeString(text, 11).copyInto(plain, 0x48)
    override val otName: String get() = decodeString(plain, 0x68, 8)
    override val originVersion: Int get() = plain.u8(0x5F)
    override val metLocation: Int get() = plain.u16(0x80)
    override var metLevel: Int
        get() = plain.u8(0x84) and 0x7F
        set(v) { plain[0x84] = ((plain.u8(0x84) and 0x80) or v.coerceIn(0, 100)).toByte() }

    override val isShiny get() = (trainerId xor secretId xor (personality shr 16).toInt() xor (personality and 0xFFFF).toInt()) < 8

    override val level: Int? get() = if (isParty) plain.u8(0x8C) else null
    override val currentHp: Int? get() = if (isParty) plain.u16(0x8E) else null
    override val maxHp: Int? get() = if (isParty) plain.u16(0x90) else null
    val partyStats: List<Int>? get() = if (isParty) List(6) { plain.u16(0x90 + it * 2) } else null

    override fun abilityId(base: BaseStats?): Int? = ability.takeIf { it != 0 }

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

    /** The decrypted record (for converting between games). */
    fun plainBytes(): ByteArray = plain.copyOf()

    override fun copy(): Pk5 = Pk5(toBytes())
    fun toPartyForm(): Pk5 = if (isParty) copy() else fromPlain(plain.copyOf(PARTY_SIZE))
    fun toBoxForm(): Pk5 = Pk5(toBytes().copyOf(BOX_SIZE))

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
            for (w in 0 until PARTY_WORDS) out.put16(0x88 + w * 2, plain.u16(0x88 + w * 2) xor partyLcg.next())
        }
        return out
    }

    companion object {
        const val BOX_SIZE = 136
        const val PARTY_SIZE = 220
        private const val PARTY_WORDS = (PARTY_SIZE - 0x88) / 2

        private val ORDERS: Array<IntArray> = arrayOf(
            "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB",
            "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
            "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA",
            "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
        ).map { s -> IntArray(4) { s[it] - 'A' } }.toTypedArray()

        val NATURES get() = GameData.NATURES

        fun fromPlain(plainBytes: ByteArray): Pk5 {
            require(plainBytes.size == BOX_SIZE || plainBytes.size == PARTY_SIZE)
            val tmp = Pk5(ByteArray(plainBytes.size))
            plainBytes.copyInto(tmp.plain)
            return Pk5(tmp.toBytes())
        }

        /** Gen 5 strings: UTF-16, ending at 0xFFFF; ♂ and ♀ use the game's own codes. */
        fun decodeString(b: ByteArray, at: Int, maxChars: Int): String = buildString {
            for (i in 0 until maxChars) {
                val c = b.u16(at + 2 * i)
                if (c == 0xFFFF || c == 0) break
                append(when (c) { 0x246D -> '♂'; 0x246E -> '♀'; else -> c.toChar() })
            }
        }

        fun encodeString(text: String, maxChars: Int): ByteArray {
            val out = ByteArray(maxChars * 2)
            val chars = text.take(maxChars - 1)
            chars.forEachIndexed { i, ch -> out.put16(2 * i, when (ch) { '♂' -> 0x246D; '♀' -> 0x246E; else -> ch.code }) }
            out.put16(2 * chars.length, 0xFFFF)
            for (i in chars.length + 1 until maxChars) out.put16(2 * i, 0)
            return out
        }
    }
}

internal class Lcg(private var seed: Long) {
    fun next(): Int {
        seed = (seed * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
        return (seed ushr 16).toInt() and 0xFFFF
    }
}
