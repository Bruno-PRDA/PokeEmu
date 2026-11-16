package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameData
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Game data read from a ROM (vanilla or hack). Every table is optional:
 * when a table could not be found it is empty, and the UI falls back to
 * showing raw numbers.
 */
data class RomData(
    override val speciesNames: List<String>,
    override val moveNames: List<String>,
    override val abilityNames: List<String>,
    override val itemNames: List<String>,
    /** Indexed by species; null entries when unknown. Empty when the table wasn't found. */
    val baseStats: List<BaseStats?>,
    /** Base PP per move; empty when not found. */
    val movePp: List<Int>,
    /** Human-readable notes on what was found, for the "ROM data" info panel. */
    override val report: List<String>,
) : GameData {
    override fun base(species: Int): BaseStats? = baseStats.getOrNull(species)
    override fun basePp(move: Int): Int? = movePp.getOrNull(move)?.takeIf { it > 0 }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(FORMAT_VERSION)
        for (list in listOf(speciesNames, moveNames, abilityNames, itemNames, report)) {
            d.writeInt(list.size); list.forEach(d::writeUTF)
        }
        d.writeInt(baseStats.size)
        for (b in baseStats) {
            d.writeBoolean(b != null)
            if (b != null) {
                b.stats.forEach(d::writeShort)
                listOf(b.type1, b.type2, b.genderRatio, b.growthRate, b.ability1, b.ability2).forEach(d::writeShort)
            }
        }
        d.writeInt(movePp.size); movePp.forEach(d::writeShort)
        d.flush()
    }

    companion object {
        /** Bump when the scanner changes so cached results are rebuilt. */
        const val FORMAT_VERSION = 2

        val TYPE_NAMES = listOf(
            "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "???",
            "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
        )

        fun typeName(id: Int) = TYPE_NAMES.getOrNull(id) ?: "Type #$id"

        /** Returns null if the cache is from another format version. */
        fun read(input: InputStream): RomData? {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != FORMAT_VERSION) return null
            fun strings() = List(d.readInt()) { d.readUTF() }
            val species = strings(); val moves = strings(); val abilities = strings(); val items = strings(); val report = strings()
            val base = List(d.readInt()) {
                if (!d.readBoolean()) null else {
                    val stats = List(6) { d.readShort().toInt() }
                    val f = List(6) { d.readShort().toInt() }
                    BaseStats(stats, f[0], f[1], f[2], f[3], f[4], f[5])
                }
            }
            val pp = List(d.readInt()) { d.readShort().toInt() }
            return RomData(species, moves, abilities, items, base, pp, report)
        }
    }
}
