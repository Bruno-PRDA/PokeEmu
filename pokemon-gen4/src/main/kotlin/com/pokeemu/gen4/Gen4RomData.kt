package com.pokeemu.gen4

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Gen 4 message (text bank) files: a u16 count and u16 seed, then an
 * encrypted (offset, length) table and encrypted UTF-16-like strings.
 */
object Gen4Messages {
    fun decode(b: ByteArray): List<String>? {
        if (b.size < 4) return null
        val count = b.u16(0)
        val seed = b.u16(2)
        if (count == 0 || 4 + count * 8 > b.size) return null
        return (0 until count).map { i ->
            val k = (seed * 0x2FD * (i + 1)) and 0xFFFF
            val key = k.toLong() or (k.toLong() shl 16)
            val offset = (b.u32(4 + i * 8) xor key).toInt()
            val length = (b.u32(8 + i * 8) xor key).toInt()
            if (offset < 0 || length < 0 || length > 0x10000 || offset + length * 2 > b.size) return null
            var charKey = (0x91BD3 * (i + 1)) and 0xFFFF
            val chars = ByteArray(length * 2)
            for (j in 0 until length) {
                chars.put16(j * 2, b.u16(offset + j * 2) xor charKey)
                charKey = (charKey + 0x493D) and 0xFFFF
            }
            Gen4Text.decode(chars, 0, length)
        }
    }
}

/**
 * Names read from a DS Pokémon ROM. Hacks add species from later generations
 * and fakemon, so these come from the ROM, never from a built-in list.
 */
class Gen4RomData(
    val speciesNames: List<String>,
    val moveNames: List<String>,
) {
    fun species(id: Int): String = speciesNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "#$id"
    fun move(id: Int): String = moveNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Move $id"

    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(FORMAT_VERSION)
        for (list in listOf(speciesNames, moveNames)) {
            d.writeInt(list.size)
            list.forEach(d::writeUTF)
        }
        d.flush()
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun read(input: InputStream): Gen4RomData? {
            val d = DataInputStream(input)
            if (d.readInt() != FORMAT_VERSION) return null
            val lists = List(2) { List(d.readInt()) { d.readUTF() } }
            return Gen4RomData(lists[0], lists[1])
        }

        /** Text archives in vanilla layouts (HGSS, Platinum, Diamond/Pearl); hacks keep these paths. */
        private val TEXT_ARCHIVES = listOf("a/0/2/7", "msgdata/pl_msg.narc", "msgdata/msg.narc")

        /**
         * Finds the species and move name banks by content (entry 1 is Bulbasaur / Pound),
         * so hacks that renumber or grow the text banks still work.
         */
        fun scan(rom: RomSource): Gen4RomData {
            val fs = NdsFileSystem(rom)
            for (path in TEXT_ARCHIVES) {
                val narcBytes = fs.read(path) ?: continue
                if (!Narc.isNarc(narcBytes)) continue
                val narc = Narc.parse(narcBytes)
                var species: List<String>? = null
                var moves: List<String>? = null
                for (i in 0 until narc.size) {
                    if (species != null && moves != null) break
                    val text = Gen4Messages.decode(narc[i]) ?: continue
                    val first = text.getOrNull(1)?.lowercase() ?: continue
                    if (species == null && first == "bulbasaur" && text.size > 400) species = text
                    if (moves == null && first == "pound" && text.size > 400) moves = text
                }
                if (species != null) return Gen4RomData(species, moves.orEmpty())
            }
            throw NdsFormatException("Couldn't find Pokémon names in this ROM")
        }
    }
}
