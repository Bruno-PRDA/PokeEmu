package com.pokeemu.gen5

import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import com.pokeemu.gen4.Gen3To4
import com.pokeemu.gen4.Pk4
import com.pokeemu.gen3.Pk3
import java.time.LocalDate

/**
 * Moves a DS (Gen 4) Pokémon into Black/White/Black 2/White 2 the way Poké
 * Transfer does: personality, IDs, IVs, EVs, experience, moves, ribbons, OT,
 * ball and game of origin are kept; its nature gets its own byte; names become
 * Gen 5 text; it's met through Poké Transfer on the day of the move, at its
 * current level; its ability keeps its slot. A GBA Pokémon goes through Gen 4
 * first (Pal Park), as in the real games. Species, moves and held item are
 * matched by name by [Transfer]. Eggs can't be moved.
 */
object Gen4To5 {
    /** Poké Transfer's location number in Gen 5. */
    const val POKE_TRANSFER = 30001

    fun register() {
        Gen3To4.register()
        Transfer.converters["pk4" to "pk5"] = Transfer.Converter(::convert, ::finish)
        Transfer.converters["pk3" to "pk5"] = Transfer.Converter({ convert(Gen3To4.convert(it)) }, ::finish)
    }

    fun convert(pk4Bytes: ByteArray): ByteArray = convert(pk4Bytes, LocalDate.now())

    fun convert(pk4Bytes: ByteArray, today: LocalDate): ByteArray {
        val pk = Pk4(pk4Bytes.copyOf(Pk4.BOX_SIZE))
        require(pk.checksumValid) { "This Pokémon's data is damaged" }
        require(!pk.isEgg) { "Eggs can't be moved into Black or White (Poké Transfer doesn't take them either)" }
        // Most of the record is laid out the same: start from the Gen 4 one.
        val p = plainOf(pk)
        p[0x14] = 70.toByte() // friendship is reset on transfer
        p[0x41] = (pk.personality % 25).toByte() // Gen 5 keeps the nature in its own byte
        p[0x42] = 0 // no hidden ability
        p[0x43] = 0
        // Platinum/HGSS-only location fields: not used in Gen 5.
        for (i in 0x44 until 0x48) p[i] = 0
        Pk5.encodeString(pk.nickname, 11).copyInto(p, 0x48)
        p[0x5E] = 0
        Pk5.encodeString(pk.otName, 8).copyInto(p, 0x68)
        p[0x7B] = (today.year - 2000).toByte(); p[0x7C] = today.monthValue.toByte(); p[0x7D] = today.dayOfMonth.toByte()
        p.put16(0x80, POKE_TRANSFER)
        p[0x85] = 0 // encounter type
        p[0x86] = 0; p[0x87] = 0 // HGSS ball / Pokéwalker data
        return Pk5.fromPlain(p).toBytes()
    }

    /** The decrypted 136-byte box record of a Gen 4 Pokémon. */
    private fun plainOf(pk: Pk4): ByteArray {
        // Pk4 doesn't expose its decrypted bytes; decrypt again here (same scheme as Gen 5).
        val raw = pk.toBoxForm().toBytes()
        return Pk5(raw).plainBytes()
    }

    /** What depends on the new game: nickname, ability slot, gender flags, met level. */
    fun finish(mon: Mon, editor: MonEditor, stored: StoredMon): List<String> {
        val pk = mon as Pk5
        val notes = mutableListOf<String>()
        val (oldNickname, oldAbilitySlot) = when (stored.format) {
            "pk3" -> Pk3(stored.bytes).let { it.nickname to it.abilitySlot }
            else -> Pk4(stored.bytes.copyOf(Pk4.BOX_SIZE)).let { it.nickname to (it.personality and 1).toInt() }
        }
        val wasNicknamed = Transfer.normalize(oldNickname) != Transfer.normalize(stored.speciesName)
        if (wasNicknamed) {
            pk.isNicknamed = true
        } else {
            pk.setNickname(editor.data.speciesName(pk.species).take(editor.maxNicknameLength))
            pk.isNicknamed = false
        }
        val base = editor.data.base(pk.species)
        if (base != null) {
            when (base.genderRatio) {
                255 -> { pk.isGenderless = true; pk.isFemale = false }
                254 -> { pk.isGenderless = false; pk.isFemale = true }
                0 -> { pk.isGenderless = false; pk.isFemale = false }
                else -> { pk.isGenderless = false; pk.isFemale = (pk.personality and 0xFF) < base.genderRatio }
            }
            // Same slot of the two regular abilities (never the hidden one).
            val options = editor.abilityOptions(pk)
            val regular = listOf(base.ability1, base.ability2).filter { it != 0 }.distinct()
            val slot = oldAbilitySlot.coerceAtMost(regular.size - 1).coerceAtLeast(0)
            if (options.isNotEmpty()) editor.setAbility(pk, options.indexOf(regular.getOrElse(slot) { options[0] }).coerceAtLeast(0))
            pk.hasHiddenAbility = false
        } else {
            notes += "This game's base stats weren't found, so its ability and gender may need fixing in the editor"
        }
        editor.level(pk)?.let { pk.metLevel = it }
        return notes
    }
}
