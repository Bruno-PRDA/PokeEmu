package com.pokeemu.gen4

import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import com.pokeemu.gen3.Pk3
import java.time.LocalDate

/**
 * Moves a GBA (Gen 3) Pokémon into a DS (Gen 4) game the way Pal Park does:
 * personality, IDs, IVs, EVs, experience, nickname, OT, ball and game of
 * origin are kept; it's met at Pal Park at its current level; its ability is
 * the same slot of the new game's species. Species, moves and held item are
 * matched by name by [Transfer]. Eggs can't be moved (as in Pal Park).
 */
object Gen3To4 {
    /** Pal Park's location number in Diamond/Pearl/Platinum/HGSS. */
    const val PAL_PARK = 55

    fun register() {
        Transfer.converters["pk3" to "pk4"] = Transfer.Converter(::convert, ::finish)
    }

    /** A Gen 3 .pk3 (80 or 100 bytes) as a Gen 4 box record. Species/moves/item are still Gen 3 numbers. */
    fun convert(pk3Bytes: ByteArray): ByteArray = convert(pk3Bytes, LocalDate.now())

    fun convert(pk3Bytes: ByteArray, today: LocalDate): ByteArray {
        val pk = Pk3(pk3Bytes)
        require(pk.checksumValid) { "This Pokémon's data is damaged" }
        require(!pk.isEgg) { "Eggs can't be moved into a DS game (Pal Park doesn't take them either)" }
        val p = ByteArray(Pk4.BOX_SIZE)
        p.put32(0, pk.personality)
        // Block A
        p.put16(0x08, pk.species)
        p.put16(0x0A, pk.heldItem)
        p.put16(0x0C, pk.trainerId)
        p.put16(0x0E, pk.secretId)
        p.put32(0x10, pk.experience)
        p[0x14] = 70.toByte() // friendship is reset on transfer
        p[0x17] = pk.language.toByte()
        for (s in 0 until 6) p[0x18 + s] = pk.ev(s).toByte()
        // Block B
        for (i in 0 until 4) {
            p.put16(0x28 + 2 * i, pk.move(i))
            p[0x30 + i] = pk.pp(i).toByte()
            p[0x34 + i] = pk.ppUps(i).toByte()
        }
        var ivWord = 0L
        for (s in 0 until 6) ivWord = ivWord or (pk.iv(s).toLong() shl (5 * s))
        p.put32(0x38, ivWord)
        // Block C
        encode(pk.nickname, 11).copyInto(p, 0x48)
        p[0x5F] = pk.originGame.toByte()
        // Block D
        encode(pk.otName, 8).copyInto(p, 0x68)
        p[0x7B] = (today.year - 2000).toByte(); p[0x7C] = today.monthValue.toByte(); p[0x7D] = today.dayOfMonth.toByte()
        p.put16(0x80, PAL_PARK) // DP met location
        p.put16(0x46, PAL_PARK) // Platinum/HGSS met location
        p[0x83] = pk.ball.coerceIn(1, 12).toByte()
        p[0x84] = ((if (pk.otFemale) 0x80 else 0) or pk.metLevel.coerceIn(0, 100)).toByte()
        p[0x86] = pk.ball.coerceIn(1, 12).toByte()
        return Pk4.fromPlain(p).toBytes()
    }

    /** What depends on the new game: ability slot, gender, nickname, met level. */
    fun finish(mon: Mon, editor: MonEditor, stored: StoredMon): List<String> {
        val pk = mon as Pk4
        val notes = mutableListOf<String>()
        val gen3 = Pk3(stored.bytes)

        // Not nicknamed in its old game: take the new game's species name.
        val wasNicknamed = Transfer.normalize(gen3.nickname) != Transfer.normalize(stored.speciesName)
        if (wasNicknamed) {
            pk.isNicknamed = true
        } else {
            val name = editor.data.speciesName(pk.species).take(editor.maxNicknameLength)
            if (editor.canEncode(name)) pk.setNickname(name)
            pk.isNicknamed = false
        }

        val base = editor.data.base(pk.species)
        if (base != null) {
            // Gender from personality, as in every game.
            when (base.genderRatio) {
                255 -> { pk.isGenderless = true; pk.isFemale = false }
                254 -> { pk.isGenderless = false; pk.isFemale = true }
                0 -> { pk.isGenderless = false; pk.isFemale = false }
                else -> { pk.isGenderless = false; pk.isFemale = (pk.personality and 0xFF) < base.genderRatio }
            }
            // Same ability slot as before.
            val options = editor.abilityOptions(pk)
            val slot = gen3.abilitySlot.coerceAtMost(options.size - 1).coerceAtLeast(0)
            if (options.isNotEmpty()) {
                runCatching { editor.setAbility(pk, slot) }.onFailure { pk.ability = options[slot] }
            }
        } else {
            notes += "This game's base stats weren't found, so its ability and gender may need fixing in the editor"
        }
        editor.level(pk)?.let { pk.metLevel = it }
        return notes
    }

    /** Gen 4 text, dropping characters it can't show. */
    private fun encode(text: String, maxChars: Int): ByteArray =
        Gen4Text.encode(text.filter { Gen4Text.canEncode(it.toString()) }.take(maxChars), maxChars)
}
