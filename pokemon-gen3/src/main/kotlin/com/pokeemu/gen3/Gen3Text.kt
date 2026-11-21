package com.pokeemu.gen3

/** Gen 3 (international) character encoding. Strings end at 0xFF. */
object Gen3Text {
    const val TERMINATOR = 0xFF

    private val decodeTable = arrayOfNulls<Char>(256).apply {
        this[0x00] = ' '
        this[0x1B] = 'é'
        for (i in 0..9) this[0xA1 + i] = '0' + i
        this[0xAB] = '!'; this[0xAC] = '?'; this[0xAD] = '.'; this[0xAE] = '-'
        this[0xB0] = '…'; this[0xB1] = '“'; this[0xB2] = '”'; this[0xB3] = '‘'; this[0xB4] = '\''
        this[0xB5] = '♂'; this[0xB6] = '♀'; this[0xB8] = ','; this[0xBA] = '/'
        for (i in 0 until 26) {
            this[0xBB + i] = 'A' + i
            this[0xD5 + i] = 'a' + i
        }
        this[0xF0] = ':'
        this[0x2D] = '&'; this[0x2E] = '+'; this[0x35] = '='; this[0x36] = ';'
        this[0x5B] = '%'; this[0x5C] = '('; this[0x5D] = ')'; this[0x85] = '<'; this[0x86] = '>'
        this[0xB7] = '$'; this[0xB9] = '×'
    }

    /** True if [b] decodes to a known character (terminator excluded). */
    fun isChar(b: Int): Boolean = decodeTable[b and 0xFF] != null

    private val encodeTable: Map<Char, Int> = buildMap {
        decodeTable.forEachIndexed { code, ch -> if (ch != null && ch !in this) put(ch, code) }
        put('’', 0xB4)
    }

    /** Decodes up to the first terminator. Unknown bytes become '?'. */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String = buildString {
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (b == TERMINATOR) break
            append(decodeTable[b] ?: '?')
        }
    }

    /** Encodes [text] into exactly [length] bytes, terminated and padded with 0xFF. */
    fun encode(text: String, length: Int): ByteArray {
        val out = ByteArray(length) { TERMINATOR.toByte() }
        text.take(length).forEachIndexed { i, c ->
            out[i] = (encodeTable[c] ?: throw IllegalArgumentException("'$c' cannot be written in a Gen 3 save")).toByte()
        }
        return out
    }

    fun canEncode(text: String): Boolean = text.all { it in encodeTable }
}
