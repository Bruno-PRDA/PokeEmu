package com.pokeemu.gen4

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
internal fun ByteArray.put16(at: Int, v: Int) {
    this[at] = v.toByte(); this[at + 1] = (v shr 8).toByte()
}
internal fun ByteArray.put32(at: Int, v: Long) {
    put16(at, (v and 0xFFFF).toInt()); put16(at + 2, ((v shr 16) and 0xFFFF).toInt())
}

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF), the Gen 4 save block checksum. */
fun crc16Ccitt(data: ByteArray, from: Int, length: Int): Int {
    var crc = 0xFFFF
    for (i in from until from + length) {
        crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
    }
    return crc
}

/**
 * Gen 4 text: 16-bit codes, 0xFFFF terminates. Covers the Latin set used by
 * English/European games and English-translated hacks; other codes show as '?'.
 */
object Gen4Text {
    private const val TERMINATOR = 0xFFFF
    private val decodeMap: Map<Int, Char> = buildMap {
        for (i in 0..9) put(0x0121 + i, '0' + i)
        for (i in 0 until 26) {
            put(0x012B + i, 'A' + i)
            put(0x0145 + i, 'a' + i)
        }
        put(0x01DE, ' '); put(0x01AB, '!'); put(0x01AC, '?'); put(0x01AD, ','); put(0x01AE, '.')
        put(0x01B3, '\''); put(0x01BE, '-'); put(0x01C4, ':'); put(0x0188, 'é')
        put(0x01BB, '♂'); put(0x01BC, '♀'); put(0x01B1, '/')
    }
    private val encodeMap = decodeMap.entries.associate { (k, v) -> v to k }

    fun decode(b: ByteArray, at: Int, maxChars: Int): String = buildString {
        for (i in 0 until maxChars) {
            val c = b.u16(at + i * 2)
            if (c == TERMINATOR) break
            append(decodeMap[c] ?: '?')
        }
    }

    fun encode(text: String, maxChars: Int): ByteArray {
        val out = ByteArray(maxChars * 2) { 0xFF.toByte() }
        text.take(maxChars - 1).forEachIndexed { i, ch ->
            out.put16(i * 2, encodeMap[ch] ?: throw IllegalArgumentException("'$ch' cannot be written in a Gen 4 save"))
        }
        return out
    }
}

/** The Gen 4 PRNG used for Pokémon encryption. */
internal class Lcg(private var seed: Long) {
    fun next(): Int {
        seed = (seed * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
        return (seed ushr 16).toInt() and 0xFFFF
    }
}
