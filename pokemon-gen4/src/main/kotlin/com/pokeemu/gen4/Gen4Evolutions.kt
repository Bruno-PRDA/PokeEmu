package com.pokeemu.gen4

import com.pokeemu.core.EvoFix
import com.pokeemu.core.EvolutionMethods
import com.pokeemu.core.Transfer

/**
 * Evolution data of a DS game: one archive file per species with entries of
 * { u16 method, u16 parameter, u16 target }.
 */
object Gen4Evolutions {
    private const val LEVEL = 4
    private const val TRADE = 5
    private const val TRADE_ITEM = 6
    private const val USE_ITEM = 7
    private const val BEAUTY = 15
    private const val HOLD_ITEM_DAY = 18
    private const val HOLD_ITEM_NIGHT = 19
    private const val MAGNETIC_FIELD = 24
    private const val MOSS_ROCK = 25
    private const val ICY_ROCK = 26
    private val ARCHIVES = listOf("a/0/3/4", "poketool/personal/evo.narc", "poketool/personal_pearl/evo.narc")

    /** HeartGold/SoulSilver (and their hacks), which lack Mt. Coronet, the Moss/Ice Rocks and Poffins. */
    fun isHgss(rom: RomSource): Boolean = String(rom.read(0x0C, 3), Charsets.ISO_8859_1) in setOf("IPK", "IPG")

    /**
     * Changes making trade evolutions (and, in HGSS, the impossible ones) happen
     * in-game: trade → level 36; trade holding an item → use that item; in HGSS
     * beauty → level 30, Mt. Coronet → Thunder Stone, Moss Rock → Leaf Stone,
     * Ice Rock → level up holding Never-Melt Ice. [itemNames] finds items by name.
     */
    fun tradeFixes(rom: RomSource, itemNames: List<String>, speciesName: (Int) -> String): List<EvoFix> {
        val fs = NdsFileSystem(rom)
        val narc = ARCHIVES.firstNotNullOfOrNull { runCatching { fs.openNarc(it) }.getOrNull()?.takeIf { n -> n.size >= 400 } } ?: return emptyList()
        val hgss = isHgss(rom)
        fun item(vararg names: String) = names.firstNotNullOfOrNull { Transfer.find(itemNames, it) }
        fun itemName(id: Int) = itemNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "item #$id"
        val thunder = item("Thunder Stone", "Thunderstone")
        val leaf = item("Leaf Stone")
        val ice = item("Never-Melt Ice", "NeverMeltIce")

        val out = mutableListOf<EvoFix>()
        for (s in 0 until narc.size) {
            val b = narc[s]
            val base = narc.romOffset(s)
            val entries = b.size / 6
            val free = (0 until entries).filter { b.u16(6 * it) == 0 && b.u16(6 * it + 4) == 0 }.toMutableList()
            for (k in 0 until entries) {
                val method = b.u16(6 * k)
                val param = b.u16(6 * k + 2)
                val target = b.u16(6 * k + 4)
                if (method == 0 || target == 0) continue
                fun fix(m: Int, p: Int, how: String, slot: Int = k) = EvoFix(
                    offset = base + 6 * slot, original = b.copyOfRange(6 * slot, 6 * slot + 6),
                    replacement = EvolutionMethods.u16(m) + EvolutionMethods.u16(p) + EvolutionMethods.u16(target),
                    species = s, target = target, how = how,
                )
                when {
                    method == TRADE -> out += fix(LEVEL, EvolutionMethods.TRADE_LEVEL, "level ${EvolutionMethods.TRADE_LEVEL}")
                    method == TRADE_ITEM -> out += fix(USE_ITEM, param, "use ${itemName(param)}")
                    hgss && method == BEAUTY -> out += fix(LEVEL, EvolutionMethods.BEAUTY_LEVEL, "level ${EvolutionMethods.BEAUTY_LEVEL}")
                    hgss && method == MAGNETIC_FIELD && thunder != null -> out += fix(USE_ITEM, thunder, "use ${itemName(thunder)}")
                    hgss && method == MOSS_ROCK && leaf != null -> out += fix(USE_ITEM, leaf, "use ${itemName(leaf)}")
                    hgss && method == ICY_ROCK && ice != null -> {
                        out += fix(HOLD_ITEM_DAY, ice, "level up holding ${itemName(ice)}")
                        free.removeFirstOrNull()?.let { slot -> out += fix(HOLD_ITEM_NIGHT, ice, "level up holding ${itemName(ice)} (night)", slot) }
                    }
                }
            }
        }
        return out
    }
}
