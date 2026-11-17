package com.pokeemu.gen4

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Random access to ROM bytes without loading the whole (often 100+ MB) image. */
interface RomSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class FileRomSource(file: File) : RomSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "read past end of ROM" }
        val out = ByteArray(length)
        raf.seek(offset); raf.readFully(out)
        return out
    }
    override fun close() = raf.close()
}

class ByteArrayRomSource(private val bytes: ByteArray) : RomSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    override fun close() {}
}

class NdsFormatException(message: String) : Exception(message)

/**
 * The NitroROM file system of a DS cartridge: the FNT (names) maps paths to
 * file ids, the FAT gives each id's byte range.
 */
class NdsFileSystem(private val rom: RomSource) {
    private val fatOffset: Long
    val fileCount: Int
    /** "a/0/2/7" -> file id. */
    val paths: Map<String, Int>

    init {
        val header = rom.read(0, 0x60)
        val fntOffset = header.u32(0x40)
        val fntSize = header.u32(0x44).toInt()
        fatOffset = header.u32(0x48)
        fileCount = (header.u32(0x4C) / 8).toInt()
        if (fntSize <= 8 || fntSize > 16 shl 20 || fntOffset + fntSize > rom.size || fatOffset + fileCount * 8L > rom.size) {
            throw NdsFormatException("No DS file system found")
        }
        val fnt = rom.read(fntOffset, fntSize)
        val dirCount = fnt.u16(6)
        if (dirCount == 0 || dirCount * 8 > fntSize) throw NdsFormatException("Bad file name table")
        val result = HashMap<String, Int>()
        fun walk(dir: Int, prefix: String, depth: Int) {
            if (depth > 32) return
            var at = fnt.u32(dir * 8).toInt()
            var fileId = fnt.u16(dir * 8 + 4)
            while (at < fntSize) {
                val type = fnt.u8(at++)
                if (type == 0) break
                val len = type and 0x7F
                if (at + len > fntSize) break
                val name = String(fnt, at, len, Charsets.ISO_8859_1)
                at += len
                if (type and 0x80 != 0) {
                    val sub = fnt.u16(at) and 0xFFF
                    at += 2
                    if (sub < dirCount) walk(sub, "$prefix$name/", depth + 1)
                } else {
                    result["$prefix$name"] = fileId++
                }
            }
        }
        walk(0, "", 0)
        paths = result
    }

    fun read(fileId: Int): ByteArray {
        require(fileId in 0 until fileCount) { "No file $fileId" }
        val entry = rom.read(fatOffset + fileId * 8L, 8)
        val start = entry.u32(0)
        val end = entry.u32(4)
        if (end < start || end > rom.size || end - start > 64 shl 20) throw NdsFormatException("Bad FAT entry $fileId")
        return rom.read(start, (end - start).toInt())
    }

    fun read(path: String): ByteArray? = paths[path]?.let(::read)

    /** A NARC read piece by piece from the ROM (only its index is loaded), or null. */
    fun openNarc(path: String): LazyNarc? {
        val id = paths[path] ?: return null
        val entry = rom.read(fatOffset + id * 8L, 8)
        val start = entry.u32(0)
        val end = entry.u32(4)
        if (end <= start || end > rom.size) return null
        return LazyNarc.open(rom, start, (end - start).toInt())
    }
}

/** A NARC inside the ROM whose sub-files are read on demand. */
class LazyNarc private constructor(private val rom: RomSource, private val dataStart: Long, private val ranges: IntArray) {
    val size get() = ranges.size / 2
    operator fun get(i: Int): ByteArray = rom.read(dataStart + ranges[2 * i], ranges[2 * i + 1] - ranges[2 * i])
    /** Where sub-file [i] starts in the ROM (for patching it in place). */
    fun romOffset(i: Int): Long = dataStart + ranges[2 * i]

    companion object {
        fun open(rom: RomSource, start: Long, length: Int): LazyNarc? {
            val head = rom.read(start, minOf(length, 0x20))
            if (!Narc.isNarc(head)) return null
            val btaf = head.u16(0x0C)
            val btafHead = rom.read(start + btaf, 12)
            val count = btafHead.u16(8)
            val btafSize = btafHead.u32(4).toInt()
            val fat = rom.read(start + btaf + 12, count * 8)
            val btnf = btaf + btafSize
            val btnfSize = rom.read(start + btnf + 4, 4).u32(0).toInt()
            val data = start + btnf + btnfSize + 8
            val ranges = IntArray(count * 2) { fat.u32(it * 4).toInt() }
            for (i in 0 until count) if (ranges[2 * i] > ranges[2 * i + 1] || data + ranges[2 * i + 1] > start + length) return null
            return LazyNarc(rom, data, ranges)
        }
    }
}

/** A NARC archive: a numbered list of sub-files. */
class Narc private constructor(private val bytes: ByteArray, private val ranges: List<IntRange>) {
    val size get() = ranges.size
    operator fun get(i: Int): ByteArray = bytes.copyOfRange(ranges[i].first, ranges[i].last + 1)

    companion object {
        fun isNarc(b: ByteArray) = b.size >= 0x1C && b[0] == 'N'.code.toByte() && b[1] == 'A'.code.toByte() &&
            b[2] == 'R'.code.toByte() && b[3] == 'C'.code.toByte()

        fun parse(b: ByteArray): Narc {
            if (!isNarc(b)) throw NdsFormatException("Not a NARC archive")
            val btaf = b.u16(0x0C)
            val count = b.u16(btaf + 8)
            val btnf = btaf + b.u32(btaf + 4).toInt()
            if (btnf + 8 > b.size) throw NdsFormatException("Truncated NARC")
            val gmif = btnf + b.u32(btnf + 4).toInt()
            val data = gmif + 8
            if (data > b.size || btaf + 12 + count * 8 > b.size) throw NdsFormatException("Truncated NARC")
            val ranges = (0 until count).map { i ->
                val s = data + b.u32(btaf + 12 + i * 8).toInt()
                val e = data + b.u32(btaf + 16 + i * 8).toInt()
                if (s > e || e > b.size) throw NdsFormatException("Bad NARC entry $i")
                s until e
            }
            return Narc(b, ranges)
        }
    }
}

/**
 * The game's main code (ARM9), decompressed when the cartridge stores it
 * compressed (retail HeartGold/SoulSilver do; most hacks and Diamond/Pearl/
 * Platinum don't). Tables such as the TM list and item icons live in it.
 */
object Arm9 {
    private const val MAX_SIZE = 8 shl 20

    fun read(rom: RomSource): ByteArray {
        val header = rom.read(0, 0x30)
        val raw = rom.read(header.u32(0x20), header.u32(0x2C).toInt().coerceIn(0, MAX_SIZE))
        val ramAddress = header.u32(0x28)
        return runCatching { decompress(raw, ramAddress) }.getOrNull() ?: raw
    }

    /**
     * Uses the module parameters (found by their "nitrocode" magic) to find
     * where the compressed part ends; null when the code isn't compressed.
     */
    internal fun decompress(arm9: ByteArray, ramAddress: Long): ByteArray? {
        val magic = byteArrayOf(0x21, 0x06, 0xC0.toByte(), 0xDE.toByte(), 0xDE.toByte(), 0xC0.toByte(), 0x06, 0x21)
        var m = 0
        while (m + 8 <= minOf(arm9.size, 0x8000) && !(0 until 8).all { arm9[m + it] == magic[it] }) m += 4
        if (m + 8 > minOf(arm9.size, 0x8000) || m < 8) return null
        val compressedEnd = arm9.u32(m - 8)
        if (compressedEnd == 0L) return null
        val end = (compressedEnd - ramAddress).toInt()
        if (end <= 8 || end > arm9.size) return null
        return blz(arm9.copyOfRange(0, end))?.plus(arm9.copyOfRange(end, arm9.size))
    }

    /** Nintendo's "backward LZ": the compressed bytes are read from the end towards the start. */
    internal fun blz(pak: ByteArray): ByteArray? {
        val n = pak.size
        val incLen = pak.u32(n - 4).toInt()
        if (incLen <= 0 || incLen > MAX_SIZE) return null
        val hdrLen = pak[n - 5].toInt() and 0xFF
        val encLen = pak.u32(n - 8).toInt() and 0xFFFFFF
        if (encLen > n || hdrLen > encLen) return null
        val decLen = n - encLen // plain prefix
        val packed = pak.copyOfRange(decLen, decLen + encLen - hdrLen).reversedArray()
        val rawLen = encLen + incLen
        val raw = ByteArray(rawLen)
        var p = 0; var r = 0; var flags = 0; var mask = 0
        while (r < rawLen) {
            mask = mask ushr 1
            if (mask == 0) { if (p >= packed.size) break; flags = packed[p++].toInt() and 0xFF; mask = 0x80 }
            if (flags and mask == 0) {
                if (p >= packed.size) break
                raw[r++] = packed[p++]
            } else {
                if (p + 1 >= packed.size) break
                val pos = ((packed[p].toInt() and 0xFF) shl 8) or (packed[p + 1].toInt() and 0xFF); p += 2
                var len = (pos ushr 12) + 3
                if (r + len > rawLen) len = rawLen - r
                val back = (pos and 0xFFF) + 3
                if (back > r) return null
                repeat(len) { raw[r] = raw[r - back]; r++ }
            }
        }
        raw.reverse()
        return pak.copyOfRange(0, decLen) + raw
    }
}
