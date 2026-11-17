package com.pokeemu.gen4

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameData
import com.pokeemu.core.Learnsets
import com.pokeemu.core.MoveInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Gen 4 message (text bank) files: a u16 count and u16 seed, then an
 * encrypted (offset, length) table and encrypted UTF-16-like strings.
 */
object Gen4Messages {
    fun decode(b: ByteArray): List<String>? {
        if (b.size < 4) return null
        val count = b.u16(0)
        val seed = b.u16(2)
        if (count == 0 || 4 + count * 8 > b.size) return null
        return (0 until count).map { i ->
            val k = (seed * 0x2FD * (i + 1)) and 0xFFFF
            val key = k.toLong() or (k.toLong() shl 16)
            val offset = (b.u32(4 + i * 8) xor key).toInt()
            val length = (b.u32(8 + i * 8) xor key).toInt()
            if (offset < 0 || length < 0 || length > 0x10000 || offset + length * 2 > b.size) return null
            var charKey = (0x91BD3 * (i + 1)) and 0xFFFF
            val chars = ByteArray(length * 2)
            for (j in 0 until length) {
                chars.put16(j * 2, b.u16(offset + j * 2) xor charKey)
                charKey = (charKey + 0x493D) and 0xFFFF
            }
            Gen4Text.decode(chars, 0, length)
        }
    }
}

/**
 * Game data read from a DS Pokémon ROM. Hacks add species from later generations
 * and fakemon and renumber things, so everything comes from the ROM, never from a
 * built-in list. Tables that weren't found are empty.
 */
class Gen4RomData(
    override val speciesNames: List<String>,
    override val moveNames: List<String>,
    override val abilityNames: List<String> = emptyList(),
    override val itemNames: List<String> = emptyList(),
    val baseStats: List<BaseStats?> = emptyList(),
    val movePp: List<Int> = emptyList(),
    /** Hacks that add the Fairy type often reuse slot 9 (the unused "???" type). */
    val fairyIsType9: Boolean = false,
    override val report: List<String> = emptyList(),
    override val learnsets: Learnsets? = null,
    /** Per move: category, power, type, accuracy (bytes 2..5 of the move data); empty when not found. */
    val moveStats: List<IntArray> = emptyList(),
) : GameData {
    override fun moveInfo(move: Int): MoveInfo? {
        val m = moveStats.getOrNull(move)?.takeIf { move > 0 } ?: return null
        return MoveInfo(
            type = m[2], power = m[1], accuracy = m[3], pp = movePp.getOrElse(move) { 0 },
            category = MoveInfo.Category.entries.getOrElse(m[0]) { MoveInfo.categoryByType(m[2], m[1]) },
        )
    }

    fun species(id: Int): String = speciesName(id)
    fun move(id: Int): String = moveName(id)
    override fun base(species: Int): BaseStats? = baseStats.getOrNull(species)
    override fun basePp(move: Int): Int? = movePp.getOrNull(move)?.takeIf { it > 0 }
    override fun typeName(id: Int): String = if (id == 9 && fairyIsType9) "Fairy" else super.typeName(id)

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(FORMAT_VERSION)
        for (list in listOf(speciesNames, moveNames, abilityNames, itemNames, report)) {
            d.writeInt(list.size)
            list.forEach(d::writeUTF)
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
        d.writeBoolean(fairyIsType9)
        d.writeBoolean(learnsets != null)
        learnsets?.write(d)
        d.writeInt(moveStats.size)
        moveStats.forEach { m -> m.forEach(d::writeByte) }
        d.flush()
    }

    companion object {
        const val FORMAT_VERSION = 4

        fun read(input: InputStream): Gen4RomData? {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != FORMAT_VERSION) return null
            val lists = List(5) { List(d.readInt()) { d.readUTF() } }
            val base = List(d.readInt()) {
                if (!d.readBoolean()) null else {
                    val stats = List(6) { d.readShort().toInt() }
                    val f = List(6) { d.readShort().toInt() }
                    BaseStats(stats, f[0], f[1], f[2], f[3], f[4], f[5])
                }
            }
            val pp = List(d.readInt()) { d.readShort().toInt() }
            val fairy = d.readBoolean()
            val learnsets = if (d.readBoolean()) Learnsets.read(d) else null
            val stats = List(d.readInt()) { IntArray(4) { d.readUnsignedByte() } }
            return Gen4RomData(lists[0], lists[1], lists[2], lists[3], base, pp, fairy, lists[4], learnsets, stats)
        }

        /** Vanilla locations (HGSS, Platinum, Diamond/Pearl); hacks keep these paths. */
        private val TEXT_ARCHIVES = listOf("a/0/2/7", "msgdata/pl_msg.narc", "msgdata/msg.narc")
        private val PERSONAL_ARCHIVES = listOf("a/0/0/2", "poketool/personal/pl_personal.narc", "poketool/personal/personal.narc")
        private val MOVE_ARCHIVES = listOf("a/0/1/1", "poketool/waza/pl_waza_tbl.narc", "poketool/waza/waza_tbl.narc")

        private const val TYPE_GRASS = 12
        private const val TYPE_POISON = 3

        /**
         * Finds each table by content rather than by file number (entry 1 is Bulbasaur,
         * Pound, Master Ball, Stench), so hacks that renumber or grow banks still work.
         */
        fun scan(rom: RomSource): Gen4RomData {
            val fs = NdsFileSystem(rom)
            val report = mutableListOf<String>()

            var species: List<String>? = null
            var moves: List<String>? = null
            var items: List<String>? = null
            var abilities: List<String>? = null
            for (path in TEXT_ARCHIVES) {
                val narc = fs.read(path)?.takeIf(Narc::isNarc)?.let(Narc::parse) ?: continue
                for (i in 0 until narc.size) {
                    if (species != null && moves != null && items != null && abilities != null) break
                    val text = Gen4Messages.decode(narc[i]) ?: continue
                    when (text.getOrNull(1)?.lowercase()) {
                        "bulbasaur" -> if (species == null && text.size > 400) species = text
                        "pound" -> if (moves == null && text.size > 400) moves = text
                        "master ball" -> if (items == null && text.size > 100) items = text
                        "stench" -> if (abilities == null && text.size > 70) abilities = text
                    }
                }
                if (species != null) {
                    report += "Text from $path"
                    break
                }
            }
            species ?: throw NdsFormatException("Couldn't find Pokémon names in this ROM")
            report += "${species.size} species names, ${moves?.size ?: 0} moves, ${items?.size ?: 0} items, ${abilities?.size ?: 0} abilities"

            val personal = personalNarc(fs)
            val base = personal?.let(::readBaseStats).orEmpty()
            report += if (base.isNotEmpty()) "Base stats for ${base.count { it != null }} species" else "Base stats not found — levels and stats can't be recalculated"
            val moveNarc = moveNarc(fs)
            val pp = moveNarc?.let { n -> (0 until n.size).map { i -> n[i].let { if (it.size >= 7) it.u8(6) else 0 } } }.orEmpty()
            val moveStats = moveNarc?.let { n -> (0 until n.size).map { i -> n[i].let { e -> if (e.size >= 7) IntArray(4) { e.u8(2 + it) } else IntArray(4) } } }.orEmpty()
            report += if (pp.isNotEmpty()) "PP for ${pp.size} moves" else "Move PP not found"
            val clefairy = species.indexOfFirst { it.equals("Clefairy", ignoreCase = true) }
            val fairy9 = clefairy > 0 && base.getOrNull(clefairy)?.type1 == 9
            if (fairy9) report += "Type 9 is Fairy in this ROM"
            val learnsets = runCatching {
                Gen4Learnsets.scan(fs, rom, personal, species.size, moves?.size?.takeIf { it > 0 } ?: 1024, report)
            }.getOrNull()

            return Gen4RomData(species, moves.orEmpty(), abilities.orEmpty(), items.orEmpty(), base, pp, fairy9, report, learnsets, moveStats)
        }

        /** Personal data: 44 bytes per species (or form). Validated against Bulbasaur. */
        private fun personalNarc(fs: NdsFileSystem): Narc? {
            for (path in PERSONAL_ARCHIVES) {
                val narc = fs.read(path)?.takeIf(Narc::isNarc)?.let(Narc::parse) ?: continue
                if (narc.size < 2) continue
                val bulba = narc[1]
                if (bulba.size < 0x1A || (0 until 6).map(bulba::u8) != listOf(45, 49, 49, 45, 65, 65)) continue
                if (bulba.u8(6) != TYPE_GRASS || bulba.u8(7) != TYPE_POISON) continue
                return narc
            }
            return null
        }

        private fun readBaseStats(narc: Narc): List<BaseStats?> {
            run {
                return (0 until narc.size).map { i ->
                    val e = narc[i]
                    if (e.size < 0x1A) null else BaseStats(
                        stats = (0 until 6).map(e::u8),
                        type1 = e.u8(6), type2 = e.u8(7),
                        genderRatio = e.u8(0x10), growthRate = e.u8(0x13).takeIf { it <= 5 } ?: 0,
                        ability1 = e.u8(0x16), ability2 = e.u8(0x17),
                    )
                }
            }
        }

        /** Move data: power at 3, type at 4, accuracy at 5, PP at 6. Validated against Pound (40 power, Normal, 100%; hacks change its PP). */
        private fun moveNarc(fs: NdsFileSystem): Narc? {
            for (path in MOVE_ARCHIVES) {
                val narc = fs.read(path)?.takeIf(Narc::isNarc)?.let(Narc::parse) ?: continue
                if (narc.size < 2) continue
                val pound = narc[1]
                if (pound.size < 7 || pound.u8(3) != 40 || pound.u8(4) != 0 || pound.u8(5) != 100) continue
                return narc
            }
            return null
        }
    }
}
