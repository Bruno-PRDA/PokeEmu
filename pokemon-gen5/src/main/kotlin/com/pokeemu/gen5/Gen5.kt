package com.pokeemu.gen5

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
internal fun ByteArray.put16(at: Int, v: Int) { this[at] = v.toByte(); this[at + 1] = (v shr 8).toByte() }
internal fun ByteArray.put32(at: Int, v: Long) { put16(at, (v and 0xFFFF).toInt()); put16(at + 2, (v shr 16).toInt()) }

/** Black, White, Black 2, White 2 (game codes IRB, IRA, IRE, IRD). */
enum class Gen5Game(val label: String, /** Where the backup copy of the save starts, relative to the main one. */ val backupDelta: Int) {
    BLACK_WHITE("Black / White", 0x24000), BLACK2_WHITE2("Black 2 / White 2", 0x26000);

    companion object {
        fun forCode(code: String): Gen5Game? = when (code.take(3)) {
            "IRB", "IRA" -> BLACK_WHITE
            "IRE", "IRD" -> BLACK2_WHITE2
            else -> null
        }
    }
}
