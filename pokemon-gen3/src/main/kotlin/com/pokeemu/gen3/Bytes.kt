package com.pokeemu.gen3

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)

internal fun ByteArray.put8(at: Int, v: Int) {
    this[at] = v.toByte()
}

internal fun ByteArray.put16(at: Int, v: Int) {
    this[at] = v.toByte()
    this[at + 1] = (v shr 8).toByte()
}

internal fun ByteArray.put32(at: Int, v: Long) {
    put16(at, (v and 0xFFFF).toInt())
    put16(at + 2, ((v shr 16) and 0xFFFF).toInt())
}
