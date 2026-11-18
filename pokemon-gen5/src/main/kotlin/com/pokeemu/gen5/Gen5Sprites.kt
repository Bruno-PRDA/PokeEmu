package com.pokeemu.gen5

import com.pokeemu.core.Picture
import com.pokeemu.core.SpriteSource
import com.pokeemu.gen4.LazyNarc
import com.pokeemu.gen4.NdsFileSystem
import com.pokeemu.gen4.RomSource

/**
 * Pokémon pictures from Black/White/Black 2/White 2: a/0/0/4 holds 20 files
 * per species (pictures, animation data, then the normal and shiny palettes).
 */
class Gen5Sprites private constructor(private val fs: NdsFileSystem, private val rom: RomSource) : SpriteSource {
    private val narc: LazyNarc? by lazy { runCatching { fs.openNarc(SPRITES) }.getOrNull() }

    override fun pokemon(species: Int, shiny: Boolean): Picture? = synchronized(this) {
        val n = narc ?: return null
        val base = species * PER_SPECIES
        if (species <= 0 || base + PER_SPECIES > n.size) return null
        val palette = palette(n[base + if (shiny) 19 else 18]) ?: return null
        // File 2: the body parts drawn side by side (a bitmap); file 4: cells (parts, made of
        // hardware objects); file 5: animations (which cell each part shows); file 6: the
        // multi-cell placing every part. Its first pose is the picture.
        val sheet = Lz.maybeDecompress(n[base + 2])?.let(::bitmap) ?: return null
        val cells = Lz.maybeDecompress(n[base + 4]) ?: return null
        val anims = Lz.maybeDecompress(n[base + 5]) ?: return null
        val multi = Lz.maybeDecompress(n[base + 6]) ?: return null
        return compose(multi, anims, cells, sheet, palette)
    }

    private val itemNarc: LazyNarc? by lazy { runCatching { fs.openNarc(ITEM_ICONS) }.getOrNull() }
    private val itemTable: ByteArray? by lazy { itemNarc?.let { runCatching { itemTable(com.pokeemu.gen4.Arm9.read(rom), it.size) }.getOrNull() } }

    /** Item icons: picture/palette pairs in a/0/2/5, found through the game code's (icon, palette) table. */
    override fun item(item: Int): Picture? = synchronized(this) {
        val n = itemNarc ?: return null
        val table = itemTable ?: return null
        if (item <= 0 || 4 * item + 4 > table.size) return null
        val icon = table.u16(4 * item)
        val pal = table.u16(4 * item + 2)
        if (icon >= n.size || pal >= n.size) return null
        val pixels = com.pokeemu.gen4.Gen4Sprites.ncgrPixels(n[icon]) ?: return null
        val palette = palette(n[pal]) ?: return null
        return Picture.fromTiles(pixels, palette, 4, 4)
    }

    companion object {
        private const val SPRITES = "a/0/0/4"
        private const val PER_SPECIES = 20

        private const val ITEM_ICONS = "a/0/2/5"

        fun open(rom: RomSource) = Gen5Sprites(NdsFileSystem(rom), rom)

        /**
         * The ARM9 table of (u16 icon, u16 palette) per item: item 0 is the "?" icon
         * (a file near the end), then items 1, 2, 3 are (2, 3), (4, 5), (6, 7).
         */
        internal fun itemTable(arm9: ByteArray, files: Int): ByteArray? {
            var i = 4
            while (i + 16 <= arm9.size) {
                if ((1..3).all { k -> arm9.u16(i + 4 * k) == 2 * k && arm9.u16(i + 4 * k + 2) == 2 * k + 1 } &&
                    arm9.u16(i) in 2 until files && arm9.u16(i + 2) in 2 until files) {
                    var n = 1
                    while (i + 4 * (n + 1) <= arm9.size && arm9.u16(i + 4 * n) in 2 until files && arm9.u16(i + 4 * n + 2) in 2 until files && n < 4096) n++
                    return arm9.copyOfRange(i, i + 4 * n)
                }
                i += 2
            }
            return null
        }

        /** First 16 colours of an NCLR ("RLCN") file. */
        internal fun palette(b: ByteArray): IntArray? {
            val d = Lz.maybeDecompress(b) ?: return null
            if (d.size < 0x28 + 32 || d[0] != 'R'.code.toByte() || d[1] != 'L'.code.toByte()) return null
            return Picture.palette(d, 0x28)
        }

        /**
         * An NCGR ("RGCN") picture: size in tiles at 0x18/0x1A; pixels either in 8x8
         * tiles or, when flagged, as plain rows ("scanned").
         */
        internal fun picture(b: ByteArray, palette: IntArray, forceScanned: Boolean = false): Picture? {
            if (b.size < 0x30 || b[0] != 'R'.code.toByte() || b[1] != 'G'.code.toByte()) return null
            val tilesHigh = b.u16(0x18)
            val tilesWide = b.u16(0x1A)
            val scanned = forceScanned || b.u8(0x24) and 1 != 0
            val size = b.u32(0x28).toInt()
            val offset = 0x18 + b.u32(0x2C).toInt()
            if (size <= 0 || offset + size > b.size) return null
            val pixels = b.copyOfRange(offset, offset + size)
            val (w, h) = if (tilesHigh in 1..64 && tilesWide in 1..64) tilesWide * 8 to tilesHigh * 8 else return null
            if (w * h / 2 > pixels.size) return null
            return if (scanned) Picture.fromLinear(pixels, palette, w, h) else Picture.fromTiles(pixels, palette, tilesWide, tilesHigh)
        }

        /** The pixel bytes of an NCGR file. */
        internal fun tileData(b: ByteArray): ByteArray? {
            if (b.size < 0x30 || b[0] != 'R'.code.toByte() || b[1] != 'G'.code.toByte()) return null
            val size = b.u32(0x28).toInt()
            val offset = 0x18 + b.u32(0x2C).toInt()
            return if (size > 0 && offset + size <= b.size) b.copyOfRange(offset, offset + size) else null
        }

        /** Object sizes in pixels by [shape][size] (square, wide, tall). */
        private val OBJ_SIZES = arrayOf(
            arrayOf(8 to 8, 16 to 16, 32 to 32, 64 to 64),
            arrayOf(16 to 8, 32 to 8, 32 to 16, 64 to 32),
            arrayOf(8 to 16, 8 to 32, 16 to 32, 32 to 64),
        )

        /** A 4-bit bitmap: width and height in pixels, 2 pixels per byte. */
        internal class Bitmap(val width: Int, val height: Int, val pixels: ByteArray) {
            fun at(x: Int, y: Int): Int {
                if (x !in 0 until width || y !in 0 until height) return 0
                val b = pixels[(y * width + x) / 2].toInt()
                return if (x % 2 == 0) b and 0xF else b shr 4 and 0xF
            }
        }

        /** An NCGR file stored as plain rows of pixels. */
        internal fun bitmap(b: ByteArray): Bitmap? {
            val data = tileData(b) ?: return null
            val w = b.u16(0x1A) * 8; val h = b.u16(0x18) * 8
            return if (w > 0 && h > 0 && w * h / 2 <= data.size) Bitmap(w, h, data) else null
        }

        /** Hardware objects of cell [index] of an NCER ("RECN") file: (x, y, w, h, hflip, vflip, tile). */
        private fun cellObjects(ncer: ByteArray, index: Int): List<IntArray> {
            val sec = 0x10
            val cellCount = ncer.u16(sec + 8)
            val extended = ncer.u16(sec + 10) == 1
            val cellsAt = sec + 8 + ncer.u32(sec + 12).toInt()
            if (index !in 0 until cellCount) return emptyList()
            val info = cellsAt + index * (if (extended) 16 else 8)
            val oamStart = cellsAt + cellCount * (if (extended) 16 else 8)
            val count = ncer.u16(info)
            val first = oamStart + ncer.u32(info + 4).toInt()
            return (0 until count).mapNotNull { i ->
                val o = first + 6 * i
                if (o + 6 > ncer.size) return@mapNotNull null
                val a0 = ncer.u16(o); val a1 = ncer.u16(o + 2); val a2 = ncer.u16(o + 4)
                val shape = a0 shr 14; val size = a1 shr 14
                if (shape > 2) return@mapNotNull null
                val (w, h) = OBJ_SIZES[shape][size]
                val y = (a0 and 0xFF).let { if (it >= 128) it - 256 else it }
                val x = (a1 and 0x1FF).let { if (it >= 256) it - 512 else it }
                val affine = a0 and 0x100 != 0
                // Rotate/scale objects with "double size" take twice the area, the picture in its middle.
                val double = affine && a0 and 0x200 != 0
                val ox = if (double) x + w / 2 else x
                val oy = if (double) y + h / 2 else y
                intArrayOf(ox, oy, w, h, if (!affine && a1 and 0x1000 != 0) 1 else 0, if (!affine && a1 and 0x2000 != 0) 1 else 0, a2 and 0x3FF)
            }
        }

        /** First frame of animation [seq] in an NANR ("RNAN") file: (cell, x, y). */
        private fun firstFrame(nanr: ByteArray, seq: Int): IntArray? {
            val sec = 0x10
            if (nanr.size < sec + 24 || nanr[0] != 'R'.code.toByte() || nanr[1] != 'N'.code.toByte()) return null
            val seqCount = nanr.u16(sec + 8)
            if (seq !in 0 until seqCount) return null
            val seqAt = sec + 8 + nanr.u32(sec + 12).toInt() + 16 * seq
            val framesAt = sec + 8 + nanr.u32(sec + 16).toInt()
            val dataAt = sec + 8 + nanr.u32(sec + 20).toInt()
            if (seqAt + 16 > nanr.size) return null
            val type = nanr.u16(seqAt + 4)
            val frame = framesAt + nanr.u32(seqAt + 12).toInt()
            if (frame + 8 > nanr.size) return null
            val d = dataAt + nanr.u32(frame).toInt()
            if (d + 2 > nanr.size) return null
            val cell = nanr.u16(d)
            // Type 2 frames also move the cell.
            val (x, y) = if (type == 2 && d + 8 <= nanr.size) nanr.u16(d + 4).toShort().toInt() to nanr.u16(d + 6).toShort().toInt() else 0 to 0
            return intArrayOf(cell, x, y)
        }

        /** Draws the first pose of an NMCR ("RCMN") multi-cell: each node shows an animation's cell at an offset. */
        internal fun compose(nmcr: ByteArray, nanr: ByteArray, ncer: ByteArray, sheet: Bitmap, palette: IntArray): Picture? {
            if (nmcr.size < 0x30 || nmcr[0] != 'R'.code.toByte() || nmcr[1] != 'C'.code.toByte()) return null
            if (ncer.size < 0x30 || ncer[0] != 'R'.code.toByte() || ncer[1] != 'E'.code.toByte()) return null
            val sec = 0x10
            val tableAt = sec + 8 + nmcr.u32(sec + 12).toInt()
            val nodesAt = sec + 8 + nmcr.u32(sec + 16).toInt()
            val nodeCount = nmcr.u16(tableAt)
            val firstNode = nodesAt + nmcr.u32(tableAt + 4).toInt()
            // (x, y, w, h, hflip, vflip, tile) placed on the canvas.
            val objs = ArrayList<IntArray>()
            for (i in 0 until nodeCount) {
                val o = firstNode + 8 * i
                if (o + 8 > nmcr.size) break
                val anim = nmcr.u16(o)
                val nx = nmcr.u16(o + 2).toShort().toInt(); val ny = nmcr.u16(o + 4).toShort().toInt()
                val (cell, fx, fy) = firstFrame(nanr, anim)?.let { Triple(it[0], it[1], it[2]) } ?: continue
                for (obj in cellObjects(ncer, cell)) objs += obj.copyOf().also { it[0] += nx + fx; it[1] += ny + fy }
            }
            if (objs.isEmpty()) return null
            val minX = objs.minOf { it[0] }; val minY = objs.minOf { it[1] }
            val width = objs.maxOf { it[0] + it[2] } - minX; val height = objs.maxOf { it[1] + it[3] } - minY
            if (width !in 1..512 || height !in 1..512) return null
            val out = IntArray(width * height)
            val sheetTilesWide = sheet.width / 8
            // Earlier nodes are drawn on top.
            for (obj in objs.asReversed()) {
                val (x, y, w, h) = obj
                val tile = obj[6]
                val sx = (tile % sheetTilesWide) * 8; val sy = (tile / sheetTilesWide) * 8
                for (py in 0 until h) for (px in 0 until w) {
                    val v = sheet.at(sx + px, sy + py)
                    if (v == 0) continue
                    val dx = if (obj[4] == 1) w - 1 - px else px
                    val dy = if (obj[5] == 1) h - 1 - py else py
                    out[(y - minY + dy) * width + x - minX + dx] = palette[v]
                }
            }
            return Picture(width, height, out)
        }

        private fun Picture.trimmedTo(max: Int): Picture =
            if (width <= max && height <= max) this
            else Picture(minOf(width, max), minOf(height, max), IntArray(minOf(width, max) * minOf(height, max)) { argb[(it / minOf(width, max)) * width + it % minOf(width, max)] })
    }
}
