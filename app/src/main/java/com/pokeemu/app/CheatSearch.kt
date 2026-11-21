package com.pokeemu.app

/**
 * Finds where a game keeps a value (money, an item count, HP...) by comparing
 * snapshots of its memory: [start] takes the first one, then each [filter]
 * keeps the addresses whose value matches. Values are little-endian unsigned
 * numbers of [size] bytes (1, 2 or 4), at addresses aligned to their size.
 */
class CheatSearch(val regions: List<Region>, val size: Int) {
    init { require(size == 1 || size == 2 || size == 4) }

    /** A block of the game's memory: bus address and length in bytes. */
    data class Region(val base: Long, val length: Int)

    enum class Compare(val label: String) {
        EQUAL("Equal to"), CHANGED("Changed"), UNCHANGED("Unchanged"), INCREASED("Increased"), DECREASED("Decreased"),
    }

    data class Result(val address: Long, val value: Long, val previous: Long)

    private var last: List<ByteArray> = emptyList()
    /** The snapshot before [last] (for showing what a value was). */
    private var before: List<ByteArray> = emptyList()
    /** Per region: offsets still in the running; null = all of them (nothing filtered yet). */
    private var candidates: List<IntArray?> = emptyList()

    /** Number of filters applied since [start]. */
    var steps = 0
        private set

    val started get() = last.isNotEmpty()

    val count: Long
        get() = regions.indices.sumOf { i -> candidates.getOrNull(i)?.size?.toLong() ?: (regions[i].length / size).toLong() }

    val maxValue: Long get() = (1L shl (8 * size)) - 1

    /** Takes the first snapshot. [read] returns a region's bytes (null if it can't be read). */
    fun start(read: (Region) -> ByteArray?): Boolean {
        val snaps = regions.map { read(it) ?: return false }
        last = snaps
        before = snaps
        candidates = regions.map { null }
        steps = 0
        return true
    }

    /** Keeps the addresses whose value now compares to the last snapshot (or to [value] for EQUAL). */
    fun filter(read: (Region) -> ByteArray?, compare: Compare, value: Long? = null): Boolean {
        check(started) { "Start a search first" }
        require(compare != Compare.EQUAL || value != null) { "Enter the value to look for" }
        val now = regions.map { read(it) ?: return false }
        candidates = regions.indices.map { r ->
            val old = last[r]; val cur = now[r]
            val keep = IntArrayBuilder()
            fun test(o: Int) {
                if (o + size > cur.size || o + size > old.size) return
                val a = valueAt(old, o); val b = valueAt(cur, o)
                val ok = when (compare) {
                    Compare.EQUAL -> b == value
                    Compare.CHANGED -> b != a
                    Compare.UNCHANGED -> b == a
                    Compare.INCREASED -> b > a
                    Compare.DECREASED -> b < a
                }
                if (ok) keep.add(o)
            }
            val list = candidates.getOrNull(r)
            if (list == null) {
                var o = 0
                while (o + size <= regions[r].length) { test(o); o += size }
            } else list.forEach(::test)
            keep.toArray()
        }
        before = last
        last = now
        steps++
        return true
    }

    /** The remaining addresses (up to [limit]) with their value now and before. */
    fun results(read: (Region) -> ByteArray?, limit: Int = 200): List<Result> {
        if (!started || count > limit) return emptyList()
        val out = ArrayList<Result>()
        regions.forEachIndexed { r, region ->
            val cur = read(region) ?: return@forEachIndexed
            val offsets = candidates.getOrNull(r) ?: return@forEachIndexed
            for (o in offsets) out += Result(region.base + o, valueAt(cur, o), valueAt(before[r], o))
        }
        return out
    }

    private fun valueAt(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** [value] as [size] little-endian bytes. */
    fun bytesOf(value: Long): ByteArray = ByteArray(size) { (value shr (8 * it)).toByte() }

    private class IntArrayBuilder {
        private var a = IntArray(64)
        private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}
