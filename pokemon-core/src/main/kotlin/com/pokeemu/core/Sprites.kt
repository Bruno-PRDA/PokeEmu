package com.pokeemu.core

/** A decoded picture: [width] x [height] ARGB pixels (alpha 0 = transparent). */
class Picture(val width: Int, val height: Int, val argb: IntArray) {
    /** The smallest part containing opaque pixels (sprites have a lot of empty space), or null if blank. */
    fun trimmed(): Picture? {
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height) for (x in 0 until width) if (argb[y * width + x] ushr 24 != 0) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        return Picture(w, h, IntArray(w * h) { argb[(y0 + it / w) * width + x0 + it % w] })
    }

    companion object {
        /** 16 BGR555 colours (colour 0 transparent) as ARGB. */
        fun palette(bytes: ByteArray, at: Int = 0): IntArray = IntArray(16) { i ->
            val c = (bytes[at + 2 * i].toInt() and 0xFF) or ((bytes[at + 2 * i + 1].toInt() and 0xFF) shl 8)
            fun ch(v: Int) = (v and 31) * 255 / 31
            if (i == 0) 0 else (0xFF shl 24) or (ch(c) shl 16) or (ch(c shr 5) shl 8) or ch(c shr 10)
        }

        /** 4-bit pixels arranged in 8x8 tiles, [tilesWide] tiles across (GBA/DS tile graphics). */
        fun fromTiles(data: ByteArray, palette: IntArray, tilesWide: Int, tilesHigh: Int): Picture {
            val w = tilesWide * 8; val h = tilesHigh * 8
            val px = IntArray(w * h)
            for (t in 0 until tilesWide * tilesHigh) for (y in 0 until 8) for (x in 0 until 8) {
                val b = data.getOrNull(t * 32 + y * 4 + x / 2)?.toInt()?.and(0xFF) ?: 0
                val v = if (x % 2 == 0) b and 0xF else b shr 4
                px[((t / tilesWide) * 8 + y) * w + (t % tilesWide) * 8 + x] = palette[v]
            }
            return Picture(w, h, px)
        }

        /** 4-bit pixels in plain rows (DS Pokémon sprites). */
        fun fromLinear(data: ByteArray, palette: IntArray, width: Int, height: Int): Picture {
            val px = IntArray(width * height)
            for (i in 0 until width * height) {
                val b = data.getOrNull(i / 2)?.toInt()?.and(0xFF) ?: 0
                px[i] = palette[if (i % 2 == 0) b and 0xF else b shr 4]
            }
            return Picture(width, height, px)
        }
    }
}

/** Pictures read from a game's ROM. Every call may return null when that picture couldn't be found. */
interface SpriteSource {
    fun pokemon(species: Int, shiny: Boolean): Picture?
    fun item(item: Int): Picture?
}
