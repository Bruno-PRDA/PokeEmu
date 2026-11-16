package com.pokeemu.gen4

class Gen4SaveException(message: String) : Exception(message)

enum class Gen4Game(val label: String) {
    DIAMOND_PEARL("Diamond / Pearl"),
    PLATINUM("Platinum"),
    HEARTGOLD_SOULSILVER("HeartGold / SoulSilver"),
    /** HGSS-based ROM hack with resized save blocks (e.g. Origin HeartGold: 24 boxes). */
    HEARTGOLD_SOULSILVER_EXPANDED("HeartGold / SoulSilver (expanded hack save)"),
}

/**
 * Where things live in a Gen 4 save (from public research, e.g. PKHeX's SAV4*).
 * The file has two 256 KiB partitions; each holds a "general" block and a
 * "storage" (PC) block. Every block ends with a footer whose last u16 is a
 * CRC16-CCITT over the block minus its footer, and whose first u32 is the
 * save counter used to pick the newer partition.
 */
data class Gen4Layout(
    val game: Gen4Game,
    val generalSize: Int,
    val storageOffset: Int,
    val storageSize: Int,
    val footerSize: Int,
    val trainerName: Int,
    val trainerId: Int,
    val money: Int,
    val partyCount: Int,
    val party: Int,
    /** Bytes per PC box inside the storage block, and where box 1 starts. */
    val boxStride: Int,
    val boxStart: Int,
    val boxCount: Int,
) {
    companion object {
        val DIAMOND_PEARL = Gen4Layout(Gen4Game.DIAMOND_PEARL, 0xC100, 0xC100, 0x121E0, 0x14,
            trainerName = 0x64, trainerId = 0x74, money = 0x78, partyCount = 0x94, party = 0x98,
            boxStride = 30 * Pk4.BOX_SIZE, boxStart = 4, boxCount = 18)
        val PLATINUM = Gen4Layout(Gen4Game.PLATINUM, 0xCF2C, 0xCF2C, 0x121E4, 0x14,
            trainerName = 0x68, trainerId = 0x78, money = 0x7C, partyCount = 0x9C, party = 0xA0,
            boxStride = 30 * Pk4.BOX_SIZE, boxStart = 4, boxCount = 18)
        val HEARTGOLD_SOULSILVER = Gen4Layout(Gen4Game.HEARTGOLD_SOULSILVER, 0xF628, 0xF700, 0x12310, 0x10,
            trainerName = 0x64, trainerId = 0x74, money = 0x78, partyCount = 0x94, party = 0x98,
            boxStride = 0x1000, boxStart = 0, boxCount = 18)
        val ALL = listOf(HEARTGOLD_SOULSILVER, PLATINUM, DIAMOND_PEARL)

        /** Footer magic every Gen 4 block footer carries, right after the block size. */
        const val FOOTER_MAGIC = 0x20060623L

        /**
         * An HGSS-style layout with non-standard block sizes. Hacks keep the vanilla general
         * block offsets but grow the blocks (Origin HeartGold: general 0xF7CC, storage 0x18408
         * at 0xF800 = 24 boxes of 0x1000).
         */
        fun hgssLike(generalSize: Int, storageOffset: Int, storageSize: Int): Gen4Layout {
            val vanilla = HEARTGOLD_SOULSILVER
            val sameAsVanilla = generalSize == vanilla.generalSize && storageOffset == vanilla.storageOffset &&
                storageSize == vanilla.storageSize
            return vanilla.copy(
                game = if (sameAsVanilla) Gen4Game.HEARTGOLD_SOULSILVER else Gen4Game.HEARTGOLD_SOULSILVER_EXPANDED,
                generalSize = generalSize, storageOffset = storageOffset, storageSize = storageSize,
                boxCount = storageSize / vanilla.boxStride,
            )
        }
    }
}

data class Gen4Trainer(val name: String, val trainerId: Int, val secretId: Int, val money: Long)

/** A parsed Gen 4 save (read-only for now). */
class Gen4Save private constructor(
    private val general: ByteArray,
    private val storage: ByteArray,
    val layout: Gen4Layout,
    /** True when only the backup partition (or only one block of each pair) was valid. */
    val usedFallback: Boolean,
) {
    val game get() = layout.game

    val trainer: Gen4Trainer
        get() = Gen4Trainer(
            name = Gen4Text.decode(general, layout.trainerName, 8),
            trainerId = general.u16(layout.trainerId),
            secretId = general.u16(layout.trainerId + 2),
            money = general.u32(layout.money),
        )

    val party: List<Pk4>
        get() {
            val n = general.u32(layout.partyCount).toInt().coerceIn(0, 6)
            return (0 until n).map { i ->
                val at = layout.party + i * Pk4.PARTY_SIZE
                Pk4(general.copyOfRange(at, at + Pk4.PARTY_SIZE))
            }
        }

    fun box(b: Int): List<Pk4?> {
        require(b in 0 until layout.boxCount)
        return (0 until 30).map { s ->
            val at = layout.boxStart + b * layout.boxStride + s * Pk4.BOX_SIZE
            Pk4(storage.copyOfRange(at, at + Pk4.BOX_SIZE)).takeUnless { it.isEmpty }
        }
    }

    companion object {
        const val FILE_SIZE = 0x80000
        const val PARTITION_SIZE = 0x40000

        /** True if the block at [start] of [size] bytes has a valid footer checksum. */
        fun blockValid(save: ByteArray, start: Int, size: Int, footerSize: Int): Boolean {
            if (start + size > save.size) return false
            val stored = save.u16(start + size - 2)
            return crc16Ccitt(save, start, size - footerSize) == stored
        }

        private fun counter(save: ByteArray, start: Int, size: Int, footerSize: Int) =
            save.u32(start + size - footerSize)

        /** Detects the game from whichever layout's checksums validate, and picks the newer partition. */
        fun parse(bytes: ByteArray): Gen4Save {
            if (bytes.size < FILE_SIZE) throw Gen4SaveException("Not a Gen 4 save (expected 512 KB, got ${bytes.size} bytes)")
            // Every layout with a valid general block is a candidate; the one saved most recently wins
            // (a hack save can sit next to stale blocks of another layout, e.g. after a ROM swap).
            val candidates = (Gen4Layout.ALL + detectHgssLike(bytes)).distinct().mapNotNull { l ->
                val counters = listOf(0, PARTITION_SIZE)
                    .filter { blockValid(bytes, it, l.generalSize, l.footerSize) }
                    .map { counter(bytes, it, l.generalSize, l.footerSize) }
                counters.maxOrNull()?.let { l to it }
            }
            if (candidates.isEmpty()) throw Gen4SaveException("No valid Diamond/Pearl, Platinum or HeartGold/SoulSilver save data found")
            for ((l, _) in candidates.sortedByDescending { it.second }) parseWith(bytes, l)?.let { return it }
            throw Gen4SaveException("PC storage checksum is invalid")
        }

        private fun parseWith(bytes: ByteArray, l: Gen4Layout): Gen4Save? {
            data class Part(val base: Int, val general: Boolean, val storage: Boolean)
            val parts = listOf(0, PARTITION_SIZE).map { base ->
                Part(base, blockValid(bytes, base, l.generalSize, l.footerSize),
                    blockValid(bytes, base + l.storageOffset, l.storageSize, l.footerSize))
            }
            if (parts.none { it.general }) return null
            val gen = parts.filter { it.general }.maxBy { counter(bytes, it.base, l.generalSize, l.footerSize) }
            val sto = parts.filter { it.storage }.maxByOrNull { counter(bytes, it.base + l.storageOffset, l.storageSize, l.footerSize) }
                ?: return null
            return Gen4Save(
                bytes.copyOfRange(gen.base, gen.base + l.generalSize),
                bytes.copyOfRange(sto.base + l.storageOffset, sto.base + l.storageOffset + l.storageSize),
                l, usedFallback = parts.count { it.general && it.storage } < 2,
            )
        }

        /**
         * Finds HGSS-style blocks by their footers (u32 counter, u32 size, u32 magic, u16 id,
         * u16 crc): the general block starts the partition and the storage block follows it,
         * 0x100-aligned. Used for hacks that resize the blocks.
         */
        private fun detectHgssLike(bytes: ByteArray): List<Gen4Layout> =
            listOf(0, PARTITION_SIZE).mapNotNull { base ->
                val generalSize = findBlockEndingFooter(bytes, base) ?: return@mapNotNull null
                val storageOffset = (generalSize + 0xFF) and 0xFF.inv()
                val storageSize = findBlockEndingFooter(bytes, base + storageOffset) ?: return@mapNotNull null
                Gen4Layout.hgssLike(generalSize, storageOffset, storageSize).takeIf {
                    blockValid(bytes, base, generalSize, it.footerSize) &&
                        blockValid(bytes, base + storageOffset, storageSize, it.footerSize)
                }
            }

        /** Size of the block starting at [start], found from a footer whose size field matches its position. */
        private fun findBlockEndingFooter(bytes: ByteArray, start: Int): Int? {
            val limit = minOf(bytes.size, (start / PARTITION_SIZE + 1) * PARTITION_SIZE)
            var magicAt = start + 8
            while (magicAt + 8 <= limit) {
                if (bytes.u32(magicAt) == Gen4Layout.FOOTER_MAGIC) {
                    val size = bytes.u32(magicAt - 4)
                    if (size == (magicAt + 8 - start).toLong()) return size.toInt()
                }
                magicAt += 4
            }
            return null
        }
    }
}
