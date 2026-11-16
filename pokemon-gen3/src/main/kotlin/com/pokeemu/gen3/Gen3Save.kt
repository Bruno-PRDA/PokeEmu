package com.pokeemu.gen3

class Gen3SaveException(message: String) : Exception(message)

data class TrainerInfo(
    val name: String,
    val isFemale: Boolean,
    val trainerId: Int,
    val secretId: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
)

/**
 * A Gen 3 battery save (128 KiB flash).
 *
 * The file holds two copies ("blocks") of the game save, each made of 14
 * rotating 4 KiB sections with an id, checksum, signature and save counter.
 * We read the newest valid block and, on [toBytes], write the edited data
 * into the *other* block with counter + 1 — exactly what the game does — so
 * the previous save stays in the file as a fallback.
 */
class Gen3Save private constructor(
    private val original: ByteArray,
    private val activeBlock: Int,
    /** Section order of the active block: physicalOrder[p] = section id at position p. */
    private val physicalOrder: IntArray,
    private val saveIndex: Long,
    /** Full 4 KiB sections of the active block, indexed by section id. */
    private val sections: Array<ByteArray>,
    val layout: SaveLayout,
    /**
     * True for saves from engines (CFRU and similar) that checksum every
     * section over 0xFF0 bytes and store extra data past the vanilla sizes.
     */
    val extendedChecksums: Boolean,
    /** How Pokémon records are stored in this save (detected from the party). */
    val pkEncoding: Pk3Encoding,
) {
    val game: Gen3Game get() = layout.game

    // ---- trainer ---------------------------------------------------------------

    val trainer: TrainerInfo
        get() {
            val s = sections[0]
            return TrainerInfo(
                name = Gen3Text.decode(s, 0x00, 7),
                isFemale = s.u8(0x08) == 1,
                trainerId = s.u16(0x0A),
                secretId = s.u16(0x0C),
                hours = s.u16(0x0E),
                minutes = s.u8(0x10),
                seconds = s.u8(0x11),
            )
        }

    private val securityKey: Long
        get() = layout.securityKey?.let { sections[0].u32(it) } ?: 0L

    var money: Long
        get() = sections[1].u32(layout.money) xor securityKey
        set(value) {
            require(value in 0..MAX_MONEY) { "money must be 0..$MAX_MONEY" }
            sections[1].put32(layout.money, value xor securityKey)
        }

    // ---- party ----------------------------------------------------------------------

    val partySize: Int get() = sections[1].u32(layout.teamSize).toInt().coerceIn(0, 6)

    val party: List<Pk3>
        get() = (0 until partySize).map { i ->
            Pk3(sections[1].copyOfRange(layout.team + i * Pk3.PARTY_SIZE, layout.team + (i + 1) * Pk3.PARTY_SIZE), pkEncoding)
        }

    /** Replaces party slot [index] (0..partySize-1). */
    fun setPartyMember(index: Int, pk: Pk3) {
        require(index in 0 until partySize) { "party slot $index is empty" }
        require(pk.isParty) { "party members need the 100-byte format" }
        pk.withEncoding(pkEncoding).toBytes().copyInto(sections[1], layout.team + index * Pk3.PARTY_SIZE)
    }

    /** Replaces the whole party (1..6 Pokémon, 100-byte form); unused slots are zeroed. */
    fun setParty(members: List<Pk3>) {
        require(members.size in 1..6) { "party must have 1..6 Pokémon" }
        require(members.all { it.isParty }) { "party members need the 100-byte format" }
        val s = sections[1]
        s.put32(layout.teamSize, members.size.toLong())
        for (i in 0 until 6) {
            val bytes = members.getOrNull(i)?.withEncoding(pkEncoding)?.toBytes() ?: ByteArray(Pk3.PARTY_SIZE)
            bytes.copyInto(s, layout.team + i * Pk3.PARTY_SIZE)
        }
    }

    // ---- PC boxes ---------------------------------------------------------------------

    /** PC storage is sections 5..13 concatenated. */
    private fun pcBuffer(): ByteArray {
        val out = ByteArray(PC_BUFFER_SIZE)
        var pos = 0
        for (id in 5..13) {
            val n = dataSize(id)
            sections[id].copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }

    private fun writePcBuffer(buf: ByteArray) {
        var pos = 0
        for (id in 5..13) {
            val n = dataSize(id)
            buf.copyInto(sections[id], 0, pos, pos + n)
            pos += n
        }
    }

    val currentBox: Int get() = pcBuffer().u32(0).toInt()

    fun boxName(box: Int): String {
        require(box in 0 until BOX_COUNT)
        return Gen3Text.decode(pcBuffer(), BOX_NAMES + box * 9, 9)
    }

    /** The 30 slots of [box]; null where the slot is empty. */
    fun box(box: Int): List<Pk3?> {
        require(box in 0 until BOX_COUNT)
        val pc = pcBuffer()
        return (0 until BOX_SLOTS).map { slot ->
            val at = 4 + (box * BOX_SLOTS + slot) * Pk3.BOX_SIZE
            Pk3(pc.copyOfRange(at, at + Pk3.BOX_SIZE), pkEncoding).takeUnless { it.isEmpty }
        }
    }

    /** Writes [pk] (box format is derived automatically) to a box slot, or clears it when null. */
    fun setBoxSlot(box: Int, slot: Int, pk: Pk3?) {
        require(box in 0 until BOX_COUNT && slot in 0 until BOX_SLOTS)
        val pc = pcBuffer()
        val bytes = pk?.withEncoding(pkEncoding)?.toBoxBytes() ?: ByteArray(Pk3.BOX_SIZE)
        bytes.copyInto(pc, 4 + (box * BOX_SLOTS + slot) * Pk3.BOX_SIZE)
        writePcBuffer(pc)
    }

    // ---- writing -----------------------------------------------------------------------

    /** Serializes the (possibly edited) save; the original block is kept as a backup copy. */
    fun toBytes(): ByteArray {
        val out = original.copyOf()
        val target = 1 - activeBlock
        val newIndex = (saveIndex + 1) and 0xFFFFFFFFL
        for (p in 0 until SECTION_COUNT) {
            val id = physicalOrder[p]
            val base = target * BLOCK_SIZE + p * SECTION_SIZE
            val section = ByteArray(SECTION_SIZE)
            sections[id].copyInto(section, 0, 0, OFF_ID)  // keep any padding bytes as the game wrote them
            section.put16(OFF_ID, id)
            section.put16(OFF_CHECKSUM, checksum(section, checksumSize(id, extendedChecksums)))
            section.put32(OFF_SIGNATURE, SIGNATURE)
            section.put32(OFF_INDEX, newIndex)
            section.copyInto(out, base)
        }
        return out
    }

    companion object {
        const val FILE_SIZE = 0x20000
        const val BLOCK_SIZE = 0xE000
        const val SECTION_SIZE = 0x1000
        const val SECTION_DATA = 0xF80
        const val SECTION_COUNT = 14
        const val BOX_COUNT = 14
        const val BOX_SLOTS = 30
        const val MAX_MONEY = 999_999L

        private const val OFF_ID = 0xFF4
        private const val OFF_CHECKSUM = 0xFF6
        private const val OFF_SIGNATURE = 0xFF8
        private const val OFF_INDEX = 0xFFC
        private const val SIGNATURE = 0x08012025L
        private const val PC_BUFFER_SIZE = 8 * 0xF80 + 0x7D0
        private const val BOX_NAMES = 4 + BOX_COUNT * BOX_SLOTS * Pk3.BOX_SIZE

        /** Bytes covered by a section's checksum: vanilla sizes, or 0xFF0 for extended (CFRU-style) saves. */
        fun checksumSize(id: Int, extended: Boolean): Int = if (extended) EXTENDED_SIZE else dataSize(id)

        private const val EXTENDED_SIZE = 0xFF0

        /** Vanilla data size of each section (also what its checksum covers in vanilla games). */
        fun dataSize(id: Int): Int = when (id) {
            0 -> 0xF2C
            4 -> 0xF08
            13 -> 0x7D0
            else -> 0xF80
        }

        fun checksum(section: ByteArray, size: Int): Int {
            var sum = 0L
            var i = 0
            while (i < size) {
                sum = (sum + section.u32(i)) and 0xFFFFFFFFL
                i += 4
            }
            return (((sum shr 16) + (sum and 0xFFFF)) and 0xFFFF).toInt()
        }

        /**
         * Parses a save. Throws [Gen3SaveException] when it isn't a readable Gen 3
         * save (wrong size, no valid block, or bad checksums in the newest block).
         */
        fun parse(bytes: ByteArray, forcedGame: Gen3Game? = null): Gen3Save {
            if (bytes.size < FILE_SIZE) throw Gen3SaveException("Not a Gen 3 save (expected 128 KB, got ${bytes.size} bytes)")
            val blocks = (0..1).mapNotNull { readBlock(bytes, it) }
            if (blocks.isEmpty()) throw Gen3SaveException("No valid save data found")
            val newest = blocks.maxBy { it.index }
            newest.badChecksum?.let { throw Gen3SaveException("Section $it of the newest save has a bad checksum") }

            val layout = SaveLayout.of(forcedGame ?: detectGame(newest.sections))
            val encoding = detectEncoding(newest.sections, layout)
            return Gen3Save(bytes.copyOf(), newest.block, newest.order, newest.index, newest.sections, layout, newest.extended, encoding)
        }

        private class Block(
            val block: Int, val index: Long, val order: IntArray, val sections: Array<ByteArray>,
            val badChecksum: Int?, val extended: Boolean,
        )

        private fun readBlock(bytes: ByteArray, block: Int): Block? {
            val order = IntArray(SECTION_COUNT)
            val sections = arrayOfNulls<ByteArray>(SECTION_COUNT)
            var index = -1L
            for (p in 0 until SECTION_COUNT) {
                val base = block * BLOCK_SIZE + p * SECTION_SIZE
                val sec = bytes.copyOfRange(base, base + SECTION_SIZE)
                if (sec.u32(OFF_SIGNATURE) != SIGNATURE) return null
                val id = sec.u16(OFF_ID)
                if (id >= SECTION_COUNT || sections[id] != null) return null
                val idx = sec.u32(OFF_INDEX)
                if (index == -1L) index = idx else if (idx != index) return null
                order[p] = id
                sections[id] = sec
            }
            @Suppress("UNCHECKED_CAST")
            val secs = sections as Array<ByteArray>
            // Every section must verify under one scheme: vanilla sizes, or 0xFF0 everywhere.
            fun firstBad(extended: Boolean) =
                (0 until SECTION_COUNT).firstOrNull { checksum(secs[it], checksumSize(it, extended)) != secs[it].u16(OFF_CHECKSUM) }
            val badVanilla = firstBad(extended = false)
            val extended = badVanilla != null && firstBad(extended = true) == null
            return Block(block, index, order, secs, if (extended) null else badVanilla, extended)
        }

        /**
         * Standard unless the party's checksums all fail while the records read
         * sensibly as plain data with a zero checksum (unencrypted hack engines).
         */
        private fun detectEncoding(sections: Array<ByteArray>, layout: SaveLayout): Pk3Encoding {
            val size = sections[1].u32(layout.teamSize).toInt().coerceIn(0, 6)
            val raws = (0 until size).map { i -> sections[1].copyOfRange(layout.team + i * Pk3.PARTY_SIZE, layout.team + (i + 1) * Pk3.PARTY_SIZE) }
            if (raws.isEmpty() || raws.all { Pk3(it).checksumValid }) return Pk3Encoding.STANDARD
            val plainOk = raws.all { r ->
                val pk = Pk3(r, Pk3Encoding.PLAIN)
                r.u16(0x1C) == 0 && pk.species in 1..2047 && pk.experience <= 2_000_000 && pk.moves.any { it != 0 }
            }
            return if (plainOk) Pk3Encoding.PLAIN else Pk3Encoding.STANDARD
        }

        /** Section 0 offset 0xAC: 0 = Ruby/Sapphire, 1 = FireRed/LeafGreen, anything else = Emerald's security key. */
        private fun detectGame(sections: Array<ByteArray>): Gen3Game {
            val code = sections[0].u32(0xAC)
            val guess = when (code) {
                0L -> Gen3Game.RUBY_SAPPHIRE
                1L -> Gen3Game.FIRERED_LEAFGREEN
                else -> Gen3Game.EMERALD
            }
            // Sanity check: the team size must be 0..6 at the guessed layout.
            fun plausible(g: Gen3Game) = sections[1].u32(SaveLayout.of(g).teamSize) in 0..6
            if (plausible(guess)) return guess
            return Gen3Game.entries.firstOrNull(::plausible) ?: guess
        }
    }
}
