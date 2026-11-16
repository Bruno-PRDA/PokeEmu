package com.pokeemu.saves

import java.security.MessageDigest

/** Identity of a GBA ROM, read from its cartridge header. */
data class RomHeader(
    /** Internal title, e.g. "POKEMON EMER". */
    val title: String,
    /** 4-character game code, e.g. "BPEE" (Emerald, USA). */
    val gameCode: String,
    val makerCode: String,
    val version: Int,
) {
    companion object {
        /** Bytes needed by [parse]. */
        const val HEADER_SIZE = 0xC0

        /** Returns null when [bytes] is not a GBA ROM (fixed header byte 0xB2 must be 0x96). */
        fun parse(bytes: ByteArray): RomHeader? {
            if (bytes.size < HEADER_SIZE || bytes[0xB2] != 0x96.toByte()) return null
            fun ascii(from: Int, len: Int) =
                String(bytes, from, len, Charsets.US_ASCII).trimEnd('\u0000', ' ').filter { it.code in 0x20..0x7E }
            return RomHeader(
                title = ascii(0xA0, 12),
                gameCode = ascii(0xAC, 4),
                makerCode = ascii(0xB0, 2),
                version = bytes[0xBC].toInt() and 0xFF,
            )
        }
    }
}

/** Stable per-ROM id used to key saves: first 16 hex chars of SHA-1. */
fun romId(rom: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(rom).joinToString("") { "%02x".format(it) }.take(16)
