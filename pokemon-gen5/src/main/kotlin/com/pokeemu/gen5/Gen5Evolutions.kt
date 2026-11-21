package com.pokeemu.gen5

import com.pokeemu.core.EvoFix
import com.pokeemu.core.EvolutionMethods
import com.pokeemu.gen4.NdsFileSystem
import com.pokeemu.gen4.RomSource

/**
 * Evolution data of Black/White/Black 2/White 2: one archive file per species
 * (a/0/1/9) with 7 entries of { u16 method, u16 parameter, u16 target }.
 */
object Gen5Evolutions {
    private const val ARCHIVE = "a/0/1/9"
    private const val LEVEL = 4
    private const val TRADE = 5
    private const val TRADE_ITEM = 6
    /** Trade for a particular Pokémon (Karrablast ↔ Shelmet). */
    private const val TRADE_SPECIES = 7
    private const val USE_ITEM = 8

    /**
     * Changes making trade evolutions happen in-game: trade (also trading for a
     * particular Pokémon) → level 36; trade holding an item → use that item.
     */
    fun tradeFixes(rom: RomSource, itemName: (Int) -> String = { "item #$it" }): List<EvoFix> {
        val narc = NdsFileSystem(rom).openNarc(ARCHIVE)?.takeIf { it.size >= 400 } ?: return emptyList()
        val out = mutableListOf<EvoFix>()
        for (s in 0 until narc.size) {
            val b = narc[s]
            val base = narc.romOffset(s)
            for (k in 0 until b.size / 6) {
                val method = b.u16(6 * k)
                val param = b.u16(6 * k + 2)
                val target = b.u16(6 * k + 4)
                if (method == 0 || target == 0) continue
                fun fix(m: Int, p: Int, how: String) = EvoFix(
                    offset = base + 6 * k, original = b.copyOfRange(6 * k, 6 * k + 6),
                    replacement = EvolutionMethods.u16(m) + EvolutionMethods.u16(p) + EvolutionMethods.u16(target),
                    species = s, target = target, how = how,
                )
                when (method) {
                    TRADE, TRADE_SPECIES -> out += fix(LEVEL, EvolutionMethods.TRADE_LEVEL, "level ${EvolutionMethods.TRADE_LEVEL}")
                    TRADE_ITEM -> out += fix(USE_ITEM, param, "use ${itemName(param)}")
                }
            }
        }
        return out
    }
}
