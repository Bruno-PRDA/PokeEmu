package com.pokeemu.gen5

class Gen5SaveException(message: String) : Exception(message)

/**
 * A Black/White or Black 2/White 2 save (512 KB). The data is split into
 * blocks; each block's CRC-16 sits 2 bytes after its end and again in a
 * checksum table (whose own CRC sits after it). Party, boxes and trainer are
 * read here; [toBytes] writes the party and boxes back with fresh checksums.
 */
class Gen5Save private constructor(private val bytes: ByteArray, val game: Gen5Game, private val tableAt: Int, private val tableLength: Int) {

    /** True when the save's backup copy (further on in the file) is in use. */
    private val hasBackup: Boolean = run {
        val at = tableAt + game.backupDelta
        at + tableLength + TABLE_CRC_GAP + 2 <= bytes.size && crc16(bytes, at, tableLength) == bytes.u16(at + tableLength + TABLE_CRC_GAP)
    }

    data class Trainer(val name: String, val trainerId: Int, val secretId: Int)

    val trainer: Trainer
        get() = Trainer(Pk5.decodeString(bytes, TRAINER + 4, 8), bytes.u16(TRAINER + 0x14), bytes.u16(TRAINER + 0x16))

    val party: List<Pk5>
        get() {
            val count = bytes.u8(PARTY + 4).coerceIn(0, 6)
            return List(count) { Pk5(bytes.copyOfRange(PARTY + 8 + it * Pk5.PARTY_SIZE, PARTY + 8 + (it + 1) * Pk5.PARTY_SIZE)) }
        }

    val boxCount get() = BOXES
    val boxSlots get() = SLOTS

    fun box(b: Int): List<Pk5?> = List(SLOTS) { s ->
        val at = boxAt(b) + s * Pk5.BOX_SIZE
        val raw = bytes.copyOfRange(at, at + Pk5.BOX_SIZE)
        if (raw.all { it.toInt() == 0 }) null else Pk5(raw).takeIf { !it.isEmpty && it.species != 0 }
    }

    fun boxName(b: Int): String = Pk5.decodeString(bytes, 0x04 + 0x28 * b, 20).ifBlank { "Box ${b + 1}" }

    /** A copy with this party and these boxes, checksums updated. */
    fun toBytes(party: List<Pk5>, boxes: List<List<Pk5?>>): ByteArray {
        require(party.size in 0..6 && boxes.size == BOXES)
        val out = bytes.copyOf()
        // Party: count, then six slots (empty ones zeroed).
        out[PARTY + 4] = party.size.toByte()
        for (i in 0 until 6) {
            val at = PARTY + 8 + i * Pk5.PARTY_SIZE
            val rec = party.getOrNull(i)?.toPartyForm()?.toBytes() ?: EMPTY_PARTY
            rec.copyInto(out, at)
        }
        boxes.forEachIndexed { b, slots ->
            require(slots.size == SLOTS)
            slots.forEachIndexed { s, mon ->
                val rec = mon?.toBoxForm()?.toBytes() ?: EMPTY_BOX
                rec.copyInto(out, boxAt(b) + s * Pk5.BOX_SIZE)
            }
        }
        val blocks = (0 until BOXES).map { boxAt(it) to BOX_LENGTH } + (PARTY to PARTY_LENGTH)
        for ((at, length) in blocks) seal(out, at, length)
        // The table's own checksum, last (it sits 0x0E bytes after the table).
        out.put16(tableAt + tableLength + TABLE_CRC_GAP, crc16(out, tableAt, tableLength))
        // The game keeps a backup copy of the save further on: keep it the same.
        if (hasBackup) {
            val delta = game.backupDelta
            val ranges = listOf(BOX_DATA until BOX_DATA + BOXES * BOX_STRIDE, PARTY until PARTY + PARTY_LENGTH + 4, tableAt until tableAt + tableLength + TABLE_CRC_GAP + 2)
            for (r in ranges) out.copyInto(out, r.first + delta, r.first, r.last + 1)
        }
        return out
    }

    /** Writes a block's checksum after it, and into the table where the old one was. */
    private fun seal(out: ByteArray, at: Int, length: Int) {
        val old = bytes.u16(at + length + 2)
        val new = crc16(out, at, length)
        out.put16(at + length + 2, new)
        tableSlot(at, length, old)?.let { out.put16(it, new) }
    }

    /** Where in the checksum table a block's checksum is kept: found by its current value. */
    private fun tableSlot(at: Int, length: Int, value: Int): Int? {
        val index = blockIndex(at)
        val guess = tableAt + 2 * index
        if (index >= 0 && guess + 2 <= tableAt + tableLength && bytes.u16(guess) == value) return guess
        val matches = (tableAt until tableAt + tableLength step 2).filter { bytes.u16(it) == value }
        return matches.singleOrNull()
    }

    /** Block order in the table: box names, the boxes, then the rest. */
    private fun blockIndex(at: Int): Int = when {
        at >= BOX_DATA && at < BOX_DATA + BOXES * BOX_STRIDE -> 1 + (at - BOX_DATA) / BOX_STRIDE
        else -> -1
    }

    companion object {
        const val SIZE = 0x80000
        private const val BOXES = 24
        private const val SLOTS = 30
        private const val BOX_DATA = 0x400
        private const val BOX_STRIDE = 0x1000
        private const val BOX_LENGTH = 0xFF0
        private const val PARTY = 0x18E00
        private const val PARTY_LENGTH = 0x534
        private const val TRAINER = 0x19400
        /** The checksum table's own checksum is this far past its end. */
        private const val TABLE_CRC_GAP = 0x0E

        private fun boxAt(b: Int) = BOX_DATA + b * BOX_STRIDE

        /** An empty slot is an all-zero record, encrypted like any other (not zeros on disk). */
        private val EMPTY_BOX: ByteArray get() = Pk5.fromPlain(ByteArray(Pk5.BOX_SIZE)).toBytes()
        private val EMPTY_PARTY: ByteArray get() = Pk5.fromPlain(ByteArray(Pk5.PARTY_SIZE)).toBytes()

        /** Checksum table (offset, length) for each game. */
        private val TABLES = listOf(
            Triple(Gen5Game.BLACK_WHITE, 0x23F00, 0x8C),
            Triple(Gen5Game.BLACK2_WHITE2, 0x25F00, 0x94),
        )

        fun parse(data: ByteArray): Gen5Save {
            if (data.size < SIZE) throw Gen5SaveException("Not a Black/White save (${data.size} bytes, expected 512 KB)")
            val bytes = data.copyOf(SIZE)
            // The game is the one whose checksum table checks out, and agrees with the party block.
            val partyOk = crc16(bytes, PARTY, PARTY_LENGTH) == bytes.u16(PARTY + PARTY_LENGTH + 2)
            for ((game, at, length) in TABLES) {
                if (crc16(bytes, at, length) == bytes.u16(at + length + TABLE_CRC_GAP) && partyOk) return Gen5Save(bytes, game, at, length)
            }
            if (!partyOk) throw Gen5SaveException("The party data's checksum doesn't match: not a Black/White save, or damaged")
            throw Gen5SaveException("Couldn't find the checksum table: not a Black/White save, or damaged")
        }

        /** CRC-16/CCITT-FALSE (poly 0x1021, start 0xFFFF), as Gen 5 saves use. */
        fun crc16(b: ByteArray, at: Int, length: Int): Int {
            var crc = 0xFFFF
            for (i in at until at + length) {
                crc = crc xor (b.u8(i) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}
