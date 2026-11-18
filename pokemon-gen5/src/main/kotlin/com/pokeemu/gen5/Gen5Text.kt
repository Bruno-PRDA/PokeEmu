package com.pokeemu.gen5

/**
 * Gen 5 message files: sections of entries, each a UTF-16 string encrypted
 * with a rolling key (decrypted from the last character backwards).
 */
object Gen5Text {
    /** All strings of the first section of a message file, or null if it isn't one. */
    fun decode(file: ByteArray): List<String>? {
        if (file.size < 12) return null
        val sections = file.u16(0)
        val count = file.u16(2)
        if (sections !in 1..8 || count > 20000) return null
        val first = file.u32(12).toInt()
        if (first <= 0 || first + 4 > file.size) return null
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val e = first + 4 + 8 * i
            if (e + 8 > file.size) return null
            val offset = first + file.u32(e).toInt()
            val length = file.u16(e + 4)
            if (offset < 0 || offset + 2 * length > file.size) return null
            out += decrypt(file, offset, length)
        }
        return out
    }

    private fun decrypt(b: ByteArray, at: Int, length: Int): String {
        if (length == 0) return ""
        val chars = IntArray(length)
        var key = b.u16(at + 2 * (length - 1)) xor 0xFFFF
        for (i in length - 1 downTo 0) {
            chars[i] = b.u16(at + 2 * i) xor key
            key = ((key ushr 3) or (key shl 13)) and 0xFFFF
        }
        val sb = StringBuilder()
        var i = 0
        while (i < length) {
            val c = chars[i]
            when {
                c == 0xFFFF -> break
                c == 0xFFFE -> sb.append('\n')
                c == 0xF000 -> i += 1 + (if (i + 2 < length) chars[i + 2] else 0) // control code: id, argument count, arguments
                c == 0x246D -> sb.append('♂')
                c == 0x246E -> sb.append('♀')
                else -> sb.append(c.toChar())
            }
            i++
        }
        return sb.toString()
    }
}
