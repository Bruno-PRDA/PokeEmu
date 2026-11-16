package com.pokeemu.gen3

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Per-species data the editor needs. Stats are in HP, Atk, Def, Spe, SpA, SpD order. */
data class BaseStats(
    val stats: List<Int>,
    val type1: Int,
    val type2: Int,
    /** 0 = always male, 254 = always female, 255 = genderless, else female if (pid & 0xFF) < ratio. */
    val genderRatio: Int,
    val growthRate: Int,
    val ability1: Int,
    val ability2: Int,
)

/**
 * Game data read from a ROM (vanilla or hack). Every table is optional:
 * when a table could not be found it is empty, and the UI falls back to
 * showing raw numbers.
 */
data class RomData(
    val speciesNames: List<String>,
    val moveNames: List<String>,
    val abilityNames: List<String>,
    val itemNames: List<String>,
    /** Indexed by species; null entries when unknown. Empty when the table wasn't found. */
    val baseStats: List<BaseStats?>,
    /** Base PP per move; empty when not found. */
    val movePp: List<Int>,
    /** Human-readable notes on what was found, for the "ROM data" info panel. */
    val report: List<String>,
) {
    fun speciesName(id: Int) = speciesNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "#$id"
    fun moveName(id: Int) = if (id == 0) "—" else moveNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Move #$id"
    fun abilityName(id: Int) = abilityNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Ability #$id"
    fun itemName(id: Int) = if (id == 0) "None" else itemNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Item #$id"
    fun base(species: Int): BaseStats? = baseStats.getOrNull(species)
    fun basePp(move: Int): Int? = movePp.getOrNull(move)?.takeIf { it > 0 }

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
        const val FORMAT_VERSION = 1

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
