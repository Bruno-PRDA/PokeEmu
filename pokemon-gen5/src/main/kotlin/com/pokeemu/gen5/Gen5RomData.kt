package com.pokeemu.gen5

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameData
import com.pokeemu.core.Learnsets
import com.pokeemu.core.MoveInfo
import com.pokeemu.gen4.Arm9
import com.pokeemu.gen4.Narc
import com.pokeemu.gen4.NdsFileSystem
import com.pokeemu.gen4.NdsFormatException
import com.pokeemu.gen4.RomSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.BitSet

/**
 * Game data read from Black, White, Black 2 or White 2 (and hacks of them).
 * Types are stored in the shared numbering (with the "???" type at 9, which
 * Gen 5 dropped), so type names and colours work as for the other games.
 */
class Gen5RomData(
    override val speciesNames: List<String>,
    override val moveNames: List<String>,
    override val abilityNames: List<String> = emptyList(),
    override val itemNames: List<String> = emptyList(),
    val baseStats: List<BaseStats?> = emptyList(),
    /** Per move: category (0 physical, 1 special, 2 status), power, type, accuracy, PP. */
    val moveStats: List<IntArray> = emptyList(),
    override val report: List<String> = emptyList(),
    override val learnsets: Learnsets? = null,
    /** Hidden ability per species (Gen 5 has three ability slots). */
    val hiddenAbilities: List<Int> = emptyList(),
) : GameData {
    override fun base(species: Int): BaseStats? = baseStats.getOrNull(species)
    override fun basePp(move: Int): Int? = moveStats.getOrNull(move)?.get(4)?.takeIf { it > 0 }
    override fun moveInfo(move: Int): MoveInfo? {
        val m = moveStats.getOrNull(move)?.takeIf { move > 0 } ?: return null
        return MoveInfo(type = m[2], power = m[1], accuracy = m[3], pp = m[4],
            category = MoveInfo.Category.entries.getOrElse(m[0]) { MoveInfo.Category.STATUS })
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(FORMAT_VERSION)
        for (list in listOf(speciesNames, moveNames, abilityNames, itemNames, report)) { d.writeInt(list.size); list.forEach(d::writeUTF) }
        d.writeInt(baseStats.size)
        for (b in baseStats) {
            d.writeBoolean(b != null)
            if (b != null) {
                b.stats.forEach(d::writeShort)
                listOf(b.type1, b.type2, b.genderRatio, b.growthRate, b.ability1, b.ability2).forEach(d::writeShort)
            }
        }
        d.writeInt(moveStats.size); moveStats.forEach { m -> m.forEach(d::writeShort) }
        d.writeInt(hiddenAbilities.size); hiddenAbilities.forEach(d::writeShort)
        d.writeBoolean(learnsets != null)
        learnsets?.write(d)
        d.flush()
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun read(input: InputStream): Gen5RomData? {
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
            val moves = List(d.readInt()) { IntArray(5) { d.readShort().toInt() } }
            val hidden = List(d.readInt()) { d.readShort().toInt() }
            val learnsets = if (d.readBoolean()) Learnsets.read(d) else null
            return Gen5RomData(lists[0], lists[1], lists[2], lists[3], base, moves, lists[4], learnsets, hidden)
        }

        private const val TEXT = "a/0/0/2"
        private const val PERSONAL = "a/0/1/6"
        private const val LEVEL_UP = "a/0/1/8"
        private const val EVOLUTIONS = "a/0/1/9"
        private const val MOVES = "a/0/2/1"
        /** Black/White, then Black 2/White 2. */
        private val EGG_MOVES = listOf("a/1/2/3", "a/1/2/4")

        /** Gen 5 numbers types without "???": Fire is 9 here, 10 in the shared list. */
        fun sharedType(t: Int) = if (t >= 9) t + 1 else t

        fun scan(rom: RomSource): Gen5RomData {
            val fs = NdsFileSystem(rom)
            val report = mutableListOf<String>()
            var species: List<String>? = null
            var moves: List<String>? = null
            var items: List<String>? = null
            var abilities: List<String>? = null
            fs.read(TEXT)?.takeIf(Narc::isNarc)?.let(Narc::parse)?.let { narc ->
                for (i in 0 until narc.size) {
                    val text = Gen5Text.decode(narc[i]) ?: continue
                    when (text.getOrNull(1)?.lowercase()) {
                        // Several files list species; the full one (with forms and Egg) is the longest.
                        "bulbasaur" -> if (text.size > (species?.size ?: 0)) species = text
                        "pound" -> if (moves == null) moves = text
                        "master ball" -> if (items == null) items = text
                        "stench" -> if (abilities == null) abilities = text
                    }
                }
            }
            species ?: throw NdsFormatException("Couldn't find Pokémon names in this ROM")
            report += "${species!!.size} species names, ${moves?.size ?: 0} moves, ${items?.size ?: 0} items, ${abilities?.size ?: 0} abilities"

            val personal = fs.read(PERSONAL)?.takeIf(Narc::isNarc)?.let(Narc::parse)
            val base = personal?.let { p -> List(p.size) { i -> p[i].takeIf { it.size >= 0x28 && i > 0 }?.let(::baseStats) } }.orEmpty()
            val hidden = personal?.let { p -> List(p.size) { i -> p[i].let { if (it.size > 26) it.u8(26) else 0 } } }.orEmpty()
            report += if (base.isNotEmpty()) "Base stats for ${base.count { it != null }} species" else "Base stats not found"

            val moveNarc = fs.read(MOVES)?.takeIf(Narc::isNarc)?.let(Narc::parse)
            val moveStats = moveNarc?.let { n ->
                // Gen 5 numbers categories status 0, physical 1, special 2.
                val category = intArrayOf(2, 0, 1)
                List(n.size) { i -> n[i].let { e -> if (e.size >= 6) intArrayOf(category.getOrElse(e.u8(2)) { 2 }, e.u8(3), sharedType(e.u8(0)), e.u8(4), e.u8(5)) else IntArray(5) } }
            }.orEmpty()
            report += if (moveStats.isNotEmpty()) "Move data for ${moveStats.size} moves" else "Move data not found"

            val learnsets = runCatching { learnsets(fs, rom, personal, species!!.size, moves?.size ?: 1024, report) }.getOrNull()
            return Gen5RomData(species!!, moves.orEmpty(), abilities.orEmpty(), items.orEmpty(), base, moveStats, report, learnsets, hidden)
        }

        private fun baseStats(e: ByteArray) = BaseStats(
            stats = List(6) { e.u8(it) },
            type1 = sharedType(e.u8(6)), type2 = sharedType(e.u8(7)),
            genderRatio = e.u8(18), growthRate = e.u8(21),
            ability1 = e.u8(24), ability2 = e.u8(25),
        )

        private fun learnsets(fs: NdsFileSystem, rom: RomSource, personal: Narc?, speciesCount: Int, moveCount: Int, report: MutableList<String>): Learnsets? {
            // Level-up: (u16 move, u16 level) pairs, ending with 0xFFFF.
            val levelUp = fs.read(LEVEL_UP)?.takeIf(Narc::isNarc)?.let(Narc::parse)?.let { n ->
                List(n.size) { i ->
                    val b = n[i]
                    (0 until b.size / 4).map { b.u16(4 * it) to b.u16(4 * it + 2) }
                        .takeWhile { (m, _) -> m != 0xFFFF }.filter { (m, l) -> m in 1 until moveCount && l <= 100 }.map { (m, l) -> l to m }
                }
            }.orEmpty()
            report += if (levelUp.isNotEmpty()) "Level-up moves for ${levelUp.size} species" else "Level-up moves not found"
            // Evolutions: 7 entries of (u16 method, u16 parameter, u16 target).
            val evolutions = fs.read(EVOLUTIONS)?.takeIf(Narc::isNarc)?.let(Narc::parse)?.let { n ->
                List(n.size) { i -> val b = n[i]; (0 until b.size / 6).mapNotNull { k -> b.u16(6 * k + 4).takeIf { b.u16(6 * k) != 0 && it in 1 until speciesCount } } }
            }.orEmpty()
            report += if (evolutions.isNotEmpty()) "Evolutions found" else "Evolutions not found"
            // Egg moves: u16 count, then the moves.
            // An archive with one small file per species: u16 count, then that many moves.
            val egg = EGG_MOVES.firstNotNullOfOrNull { path ->
                fs.read(path)?.takeIf(Narc::isNarc)?.let(Narc::parse)?.takeIf { it.size >= speciesCount / 2 }?.let { n ->
                    val lists = buildMap { for (i in 0 until n.size) { val b = n[i]; if (b.size >= 2) { val c = b.u16(0); if (c in 1..60 && 2 + 2 * c <= b.size && b.size <= 2 + 2 * c + 4) put(i, List(c) { b.u16(2 + 2 * it) }) } } }
                    lists.takeIf { l -> l.size >= 100 && l.values.all { ms -> ms.all { it in 1 until moveCount } } }
                }
            }.orEmpty()
            report += if (egg.isNotEmpty()) "Egg moves for ${egg.size} species" else "Egg moves not found"
            // TMs: the list is in the game code (TM01-92, HM01-06, TM93-95); compatibility bits in the personal data.
            val tm = tmList(rom, moveCount)
            val compat = personal?.let { p -> List(p.size) { i -> p[i].let { e -> if (e.size >= 0x28 + 13) BitSet.valueOf(e.copyOfRange(0x28, 0x28 + 13)) else BitSet() } } }
            report += if (tm != null && compat != null) "TM/HM compatibility found" else "TM/HM compatibility not found"
            if (levelUp.isEmpty() && evolutions.isEmpty() && tm == null) return null
            return Learnsets(
                levelUp = levelUp,
                tmMoves = tm.orEmpty(), hmCount = if (tm != null) 6 else 0,
                tmCompat = if (tm != null) compat.orEmpty() else emptyList(),
                eggMoves = egg,
                evolutions = evolutions,
            )
        }

        /**
         * The game code lists TM01-92, then HM01-06, then TM93-95. Returned in
         * compatibility-bit order: TM01-95, then HM01-06.
         */
        private fun tmList(rom: RomSource, moveCount: Int): List<Int>? {
            val arm9 = Arm9.read(rom)
            val hms = listOf(15, 19, 57, 70, 127, 291) // Cut, Fly, Surf, Strength, Waterfall, Dive
            val pattern = ByteArray(12).also { b -> hms.forEachIndexed { i, m -> b.put16(2 * i, m) } }
            var at = 2 * 92
            while (at + 12 + 6 <= arm9.size) {
                if ((0 until 12).all { arm9[at + it] == pattern[it] }) {
                    val start = at - 2 * 92
                    val first = List(92) { arm9.u16(start + 2 * it) }
                    val last = List(3) { arm9.u16(at + 12 + 2 * it) }
                    if ((first + last).all { it in 1 until moveCount }) return first + last + hms
                }
                at += 2
            }
            return null
        }
    }
}
