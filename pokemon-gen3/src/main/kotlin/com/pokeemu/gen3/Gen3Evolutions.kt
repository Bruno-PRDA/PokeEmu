package com.pokeemu.gen3

import com.pokeemu.core.EvoFix
import com.pokeemu.core.EvolutionMethods
import java.util.BitSet

/**
 * The evolution table of a GBA game: per species, slots of
 * { u16 method, u16 parameter, u16 target, u16 padding } (5 slots in vanilla,
 * more in expanded engines).
 */
object Gen3Evolutions {
    private const val TRADE = 5
    private const val TRADE_ITEM = 6
    private const val USE_ITEM = 7
    private const val LEVEL = 4
    private const val BEAUTY = 15

    private val STRIDES = listOf(40, 48, 64, 80, 128, 160, 256)
    /** First-generation starters, Caterpie and Pidgey lines: kept by every game and hack. */
    private val ANCHORS = listOf(1 to 2, 2 to 3, 4 to 5, 5 to 6, 7 to 8, 8 to 9, 10 to 11, 11 to 12, 16 to 17, 17 to 18)

    /** (table offset, bytes per species), among tables the code points to ([referenced]). */
    fun locate(rom: ByteArray, referenced: BitSet, speciesCount: Int): Pair<Int, Int>? {
        fun target(t: Int, stride: Int, species: Int) = (t + stride * species + 4).takeIf { it + 2 <= rom.size }?.let(rom::u16)
        var t = referenced.nextSetBit(0)
        while (t >= 0) {
            if (t % 4 == 0) for (stride in STRIDES) {
                if (t + stride * speciesCount > rom.size) continue
                if (ANCHORS.all { (from, to) -> target(t, stride, from) == to }) return t to stride
            }
            t = referenced.nextSetBit(t + 1)
        }
        return null
    }

    /** Offsets some ROM pointer points to. */
    fun referenced(rom: ByteArray): BitSet {
        val set = BitSet(rom.size)
        var i = 0
        while (i + 4 <= rom.size) {
            val b3 = rom[i + 3].toInt() and 0xFF
            if (b3 == 0x08 || b3 == 0x09) {
                val target = rom.u32(i) - 0x08000000L
                if (target in 0 until rom.size) set.set(target.toInt())
            }
            i += 4
        }
        return set
    }

    /**
     * Changes that make trade evolutions happen in-game: a trade becomes a
     * level-up at 36, a trade holding an item becomes using that item (like a
     * stone). With [fixBeauty] (FireRed/LeafGreen, which have no way to raise
     * beauty), beauty becomes a level-up at 30.
     */
    fun tradeFixes(rom: ByteArray, table: Int, stride: Int, speciesCount: Int, fixBeauty: Boolean, itemName: (Int) -> String): List<EvoFix> {
        val out = mutableListOf<EvoFix>()
        for (s in 0 until speciesCount) for (slot in 0 until stride / 8) {
            val at = table + stride * s + 8 * slot
            if (at + 8 > rom.size) break
            val method = rom.u16(at)
            val param = rom.u16(at + 2)
            val target = rom.u16(at + 4)
            if (target == 0 || target >= speciesCount) continue
            val (newMethod, newParam, how) = when (method) {
                TRADE -> Triple(LEVEL, EvolutionMethods.TRADE_LEVEL, "level ${EvolutionMethods.TRADE_LEVEL}")
                TRADE_ITEM -> Triple(USE_ITEM, param, "use ${itemName(param)}")
                BEAUTY -> if (fixBeauty) Triple(LEVEL, EvolutionMethods.BEAUTY_LEVEL, "level ${EvolutionMethods.BEAUTY_LEVEL}") else continue
                else -> continue
            }
            out += EvoFix(
                offset = at.toLong(),
                original = rom.copyOfRange(at, at + 4),
                replacement = EvolutionMethods.u16(newMethod) + EvolutionMethods.u16(newParam),
                species = s, target = target, how = how,
            )
        }
        return out
    }
}
