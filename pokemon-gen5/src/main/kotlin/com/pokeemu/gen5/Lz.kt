package com.pokeemu.gen5

/** Nintendo's LZ77 (type 0x10) and LZ11 (type 0x11) compression, as DS games store files. */
internal object Lz {
    fun isCompressed(b: ByteArray) = b.size >= 4 && (b[0].toInt() == 0x10 || b[0].toInt() == 0x11)

    /** The decompressed file, or [b] itself when it isn't compressed; null when damaged. */
    fun maybeDecompress(b: ByteArray): ByteArray? = if (isCompressed(b)) decompress(b) else b

    fun decompress(b: ByteArray): ByteArray? {
        if (!isCompressed(b)) return null
        val lz11 = b[0].toInt() == 0x11
        var size = b.u8(1) or (b.u8(2) shl 8) or (b.u8(3) shl 16)
        var p = 4
        if (size == 0 && b.size >= 8) { size = b.u32(4).toInt(); p = 8 }
        if (size <= 0 || size > (16 shl 20)) return null
        val out = ByteArray(size)
        var o = 0
        while (o < size) {
            if (p >= b.size) return null
            val flags = b.u8(p++)
            for (bit in 7 downTo 0) {
                if (o >= size) break
                if (flags shr bit and 1 == 0) {
                    if (p >= b.size) return null
                    out[o++] = b[p++]
                    continue
                }
                if (p + 1 >= b.size) return null
                val length: Int
                val disp: Int
                if (!lz11) {
                    val b1 = b.u8(p); val b2 = b.u8(p + 1); p += 2
                    length = (b1 shr 4) + 3
                    disp = ((b1 and 0xF) shl 8 or b2) + 1
                } else {
                    val b1 = b.u8(p)
                    when (b1 shr 4) {
                        0 -> { // 8-bit length
                            if (p + 2 >= b.size) return null
                            val b2 = b.u8(p + 1); val b3 = b.u8(p + 2); p += 3
                            length = ((b1 and 0xF) shl 4 or (b2 shr 4)) + 0x11
                            disp = ((b2 and 0xF) shl 8 or b3) + 1
                        }
                        1 -> { // 16-bit length
                            if (p + 3 >= b.size) return null
                            val b2 = b.u8(p + 1); val b3 = b.u8(p + 2); val b4 = b.u8(p + 3); p += 4
                            length = ((b1 and 0xF) shl 12 or (b2 shl 4) or (b3 shr 4)) + 0x111
                            disp = ((b3 and 0xF) shl 8 or b4) + 1
                        }
                        else -> {
                            val b2 = b.u8(p + 1); p += 2
                            length = (b1 shr 4) + 1
                            disp = ((b1 and 0xF) shl 8 or b2) + 1
                        }
                    }
                }
                if (disp > o) return null
                repeat(length) { if (o < size) { out[o] = out[o - disp]; o++ } }
            }
        }
        return out
    }
}
