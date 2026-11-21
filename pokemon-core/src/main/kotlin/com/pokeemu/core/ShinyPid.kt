package com.pokeemu.core

import kotlin.random.Random

/** Personality values that make a Pokémon shiny while keeping what else the value decides. */
object ShinyPid {
    fun isShiny(pid: Long, trainerId: Int, secretId: Int) =
        (trainerId xor secretId xor (pid shr 16).toInt() xor (pid and 0xFFFF).toInt()) < 8

    /**
     * A shiny personality value for [trainerId]/[secretId] with the same nature
     * (value mod 25) and the same low byte (gender, and the GBA ability bit) as [pid].
     */
    /**
     * The writes that give every copy of one Pokemon in [ram] the personality
     * [newPid]. Games keep several copies of a wild Pokemon (party record,
     * battle struct...), and catching may use any of them. At each 4-byte
     * aligned [oldPid]: a whole record (one of [recordSizes], accepted and
     * re-encoded by [reencode]) is rewritten; otherwise just those 4 bytes.
     */
    fun rewrites(ram: ByteArray, oldPid: Long, newPid: Long, recordSizes: List<Int>, reencode: (ByteArray) -> ByteArray?): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        val raw = ByteArray(4) { (newPid shr (8 * it)).toByte() }
        var p = 0
        while (p + 4 <= ram.size) {
            val v = (ram[p].toLong() and 0xFF) or ((ram[p + 1].toLong() and 0xFF) shl 8) or
                ((ram[p + 2].toLong() and 0xFF) shl 16) or ((ram[p + 3].toLong() and 0xFF) shl 24)
            if (v == oldPid) {
                val record = recordSizes.firstNotNullOfOrNull { size ->
                    if (p + size <= ram.size) reencode(ram.copyOfRange(p, p + size))?.takeIf { it.size == size } else null
                }
                if (record != null) { out += p to record; p += record.size; continue }
                out += p to raw.copyOf()
            }
            p += 4
        }
        return out
    }

    /**
     * True when [copy] (decoded from RAM) is the same Pokemon as [mon]. A party-size
     * copy must also match the party section, since the checksum doesn't cover it.
     */
    fun sameMon(copy: Mon, mon: Mon) = copy.checksumValid && copy.species == mon.species &&
        (copy.level == null || (copy.level == mon.level && copy.maxHp == mon.maxHp))

    fun reroll(pid: Long, trainerId: Int, secretId: Int, random: Random = Random.Default): Long {
        val low = (pid and 0xFF).toInt()
        val nature = pid % 25
        val ids = trainerId xor secretId
        repeat(100_000) {
            // Pick the high half, then the only low half (with this low byte) that's shiny.
            val k = random.nextInt(8)
            val highLowByte = (ids and 0xFF) xor low xor k
            val high = (random.nextInt(256) shl 8) or highLowByte
            val lowHalf = ((ids xor high) and 0xFF00) or low
            val candidate = (high.toLong() shl 16) or lowHalf.toLong()
            if (candidate % 25 == nature && isShiny(candidate, trainerId, secretId)) return candidate
        }
        error("no shiny value found")
    }
}
