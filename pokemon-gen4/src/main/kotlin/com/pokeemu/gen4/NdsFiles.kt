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
    private val fileCount: Int
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
