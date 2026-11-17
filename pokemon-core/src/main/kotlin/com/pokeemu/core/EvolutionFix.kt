package com.pokeemu.core

/**
 * One change to a ROM's evolution data, making an evolution possible without
 * trading (or another condition the player can't meet): [replacement] goes
 * at [offset], and [original] is kept so the change can be undone exactly.
 */
class EvoFix(
    val offset: Long,
    val original: ByteArray,
    val replacement: ByteArray,
    val species: Int,
    val target: Int,
    /** What it becomes, e.g. "level 36" or "use Metal Coat". */
    val how: String,
)

object EvolutionMethods {
    /** Trades become level-ups at this level. */
    const val TRADE_LEVEL = 36
    /** Beauty (Feebas) becomes a level-up at this level. */
    const val BEAUTY_LEVEL = 30

    fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}
