package com.pokeemu.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** How a Pokémon can learn a move. [via] is the earlier evolution it learns it as, if not itself. */
data class MoveSource(val kind: Kind, val detail: Int = 0, val via: Int? = null) {
    enum class Kind { LEVEL, TM, HM, EGG, TUTOR, TEACHABLE }
}

/**
 * Which moves each species can learn, as read from a ROM. Any part can be
 * missing (empty) when that table wasn't found; [has] says which were.
 *
 * A Pokémon can also know what it learned before evolving, so [learnable]
 * includes every earlier evolution's moves (and the egg moves of the first one).
 */
class Learnsets(
    /** Per species: (level, move), in game order. Level 0 = learned on evolving. */
    val levelUp: List<List<Pair<Int, Int>>> = emptyList(),
    /** TM moves then HM moves, in item order. */
    val tmMoves: List<Int> = emptyList(),
    val hmCount: Int = 0,
    /** Per species: which entries of [tmMoves] it can learn (bit i = tmMoves[i]). */
    val tmCompat: List<java.util.BitSet> = emptyList(),
    /** Per species: egg moves (only basic species have any). */
    val eggMoves: Map<Int, List<Int>> = emptyMap(),
    val tutorMoves: List<Int> = emptyList(),
    val tutorCompat: List<java.util.BitSet> = emptyList(),
    /** Per species: what it evolves into. */
    val evolutions: List<List<Int>> = emptyList(),
    /** True when [tutorMoves] lists every taught move (TMs, HMs and tutors together, as some engines store them). */
    val tutorIncludesTms: Boolean = false,
) {
    enum class Part { LEVEL_UP, TM, EGG, TUTOR, EVOLUTIONS }

    val has: Set<Part> = buildSet {
        if (levelUp.isNotEmpty()) add(Part.LEVEL_UP)
        if (tmMoves.isNotEmpty() && tmCompat.isNotEmpty()) add(Part.TM)
        if (eggMoves.isNotEmpty()) add(Part.EGG)
        if (tutorMoves.isNotEmpty() && tutorCompat.isNotEmpty()) {
            add(Part.TUTOR)
            if (tutorIncludesTms) add(Part.TM)
        }
        if (evolutions.isNotEmpty()) add(Part.EVOLUTIONS)
    }

    private val preEvolution: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        evolutions.forEachIndexed { from, targets -> targets.forEach { to -> if (to != from) m.putIfAbsent(to, from) } }
        m
    }

    /** [species] and the species it evolved from, nearest first (cycles guarded). */
    fun lineage(species: Int): List<Int> {
        val out = mutableListOf(species)
        var s = species
        while (out.size < 8) {
            s = preEvolution[s] ?: break
            if (s in out) break
            out += s
        }
        return out
    }

    /** Every move [species] can know, with the first way found to learn it. Empty if no learnset data. */
    fun learnable(species: Int): Map<Int, MoveSource> {
        val out = LinkedHashMap<Int, MoveSource>()
        val line = lineage(species)
        for (s in line) {
            val via = s.takeIf { it != species }
            for ((level, move) in levelUp.getOrNull(s).orEmpty()) out.putIfAbsent(move, MoveSource(MoveSource.Kind.LEVEL, level, via))
            tmCompat.getOrNull(s)?.let { bits ->
                var i = bits.nextSetBit(0)
                while (i >= 0 && i < tmMoves.size) {
                    val hm = i >= tmMoves.size - hmCount
                    val number = if (hm) i - (tmMoves.size - hmCount) + 1 else i + 1
                    out.putIfAbsent(tmMoves[i], MoveSource(if (hm) MoveSource.Kind.HM else MoveSource.Kind.TM, number, via))
                    i = bits.nextSetBit(i + 1)
                }
            }
            tutorCompat.getOrNull(s)?.let { bits ->
                var i = bits.nextSetBit(0)
                while (i >= 0 && i < tutorMoves.size) {
                    out.putIfAbsent(tutorMoves[i], MoveSource(if (tutorIncludesTms) MoveSource.Kind.TEACHABLE else MoveSource.Kind.TUTOR, i + 1, via))
                    i = bits.nextSetBit(i + 1)
                }
            }
        }
        // Egg moves come from the first species of the line (whatever hatches).
        for (s in line.asReversed()) for (move in eggMoves[s].orEmpty()) {
            out.putIfAbsent(move, MoveSource(MoveSource.Kind.EGG, 0, s.takeIf { it != species }))
        }
        out.remove(0)
        return out
    }

    private fun fields() = listOf(levelUp, tmMoves, hmCount, tmCompat, eggMoves, tutorMoves, tutorCompat, evolutions, tutorIncludesTms)
    override fun equals(other: Any?) = other is Learnsets && other.fields() == fields()
    override fun hashCode() = fields().hashCode()

    fun write(d: DataOutputStream) {
        fun bits(list: List<java.util.BitSet>) {
            d.writeInt(list.size)
            list.forEach { b -> val bytes = b.toByteArray(); d.writeShort(bytes.size); d.write(bytes) }
        }
        fun ints(list: List<Int>) { d.writeInt(list.size); list.forEach(d::writeShort) }
        d.writeInt(levelUp.size)
        levelUp.forEach { l -> d.writeShort(l.size); l.forEach { (lv, m) -> d.writeShort(lv); d.writeShort(m) } }
        ints(tmMoves); d.writeShort(hmCount); bits(tmCompat)
        d.writeInt(eggMoves.size)
        eggMoves.forEach { (s, moves) -> d.writeShort(s); ints(moves) }
        ints(tutorMoves); bits(tutorCompat)
        d.writeInt(evolutions.size)
        evolutions.forEach(::ints)
        d.writeBoolean(tutorIncludesTms)
    }

    companion object {
        fun read(d: DataInputStream): Learnsets {
            fun bits() = List(d.readInt()) { java.util.BitSet.valueOf(ByteArray(d.readShort().toInt()).also(d::readFully)) }
            fun ints() = List(d.readInt()) { d.readShort().toInt() and 0xFFFF }
            val levelUp = List(d.readInt()) { List(d.readShort().toInt()) { (d.readShort().toInt() and 0xFFFF) to (d.readShort().toInt() and 0xFFFF) } }
            val tm = ints(); val hm = d.readShort().toInt(); val tmCompat = bits()
            val egg = HashMap<Int, List<Int>>().apply { repeat(d.readInt()) { put(d.readShort().toInt() and 0xFFFF, ints()) } }
            val tutor = ints(); val tutorCompat = bits()
            val evos = List(d.readInt()) { ints() }
            val teachable = d.readBoolean()
            return Learnsets(levelUp, tm, hm, tmCompat, egg, tutor, tutorCompat, evos, teachable)
        }

        /** Parses an egg-move list: (20000 + species) followed by its moves, ending with 0xFFFF. */
        fun parseEggMoves(words: (Int) -> Int?, speciesCount: Int, moveCount: Int): Map<Int, List<Int>>? {
            val out = LinkedHashMap<Int, MutableList<Int>>()
            var current: MutableList<Int>? = null
            var i = 0
            while (i < 20000) {
                val v = words(i) ?: return null
                when {
                    v == 0xFFFF -> return out.takeIf { it.size >= 20 }
                    v > EGG_MARKER && v - EGG_MARKER < speciesCount -> current = out.getOrPut(v - EGG_MARKER) { mutableListOf() }
                    v in 1 until moveCount && current != null -> current += v
                    else -> return null
                }
                i++
            }
            return null
        }

        const val EGG_MARKER = 20000
    }
}

/** Label for a [MoveSource], e.g. "Lv 16", "TM24", "Egg move (as Charmander)". */
fun MoveSource.label(speciesName: (Int) -> String): String {
    val base = when (kind) {
        MoveSource.Kind.LEVEL -> if (detail == 0) "On evolving" else "Lv $detail"
        MoveSource.Kind.TM -> "TM%02d".format(detail)
        MoveSource.Kind.HM -> "HM%02d".format(detail)
        MoveSource.Kind.EGG -> "Egg move"
        MoveSource.Kind.TUTOR -> "Tutor"
        MoveSource.Kind.TEACHABLE -> "TM / tutor"
    }
    return via?.let { "$base (as ${speciesName(it)})" } ?: base
}
