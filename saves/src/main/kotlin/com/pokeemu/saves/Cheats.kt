package com.pokeemu.saves

import java.io.File

/** One cheat: a name the user gave it and its code lines, e.g. ["82025840 0063"]. */
data class Cheat(val name: String, val lines: List<String>, val enabled: Boolean = true) {
    /** The form the emulator cores take: lines joined with "+". */
    val coreCode: String get() = lines.joinToString("+")
}

/**
 * A game's cheats, stored as games/<romId>/cheats.txt (next to its saves, so
 * they survive removing and re-adding the game). One cheat per line:
 * `1|name|LINE+LINE` (1 = on, 0 = off).
 */
class CheatStore(storage: GameStorage, romId: String) {
    private val file = File(storage.gameDir(romId), "cheats.txt")
    private val evolveFlag = File(storage.gameDir(romId), "cheat-evolve-without-trading")

    /**
     * Built-in cheat: trade evolutions (and other impossible ones) happen by
     * levelling up or using the item. Applied to the game's data in memory at
     * start; the ROM file is never changed.
     */
    var evolveWithoutTrading: Boolean
        get() = evolveFlag.isFile
        set(on) { if (on) evolveFlag.writeText("on") else evolveFlag.delete() }

    fun load(): List<Cheat> = runCatching {
        file.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            Cheat(parts[1], parts[2].split('+').filter { it.isNotBlank() }, parts[0] == "1")
        }
    }.getOrDefault(emptyList())

    fun save(cheats: List<Cheat>) {
        val text = cheats.joinToString("") { c ->
            val name = c.name.replace(Regex("\\s*[|\\r\\n]+\\s*"), " ").trim()
            "${if (c.enabled) 1 else 0}|$name|${c.coreCode}\n"
        }
        GameStorage.writeAtomically(file, text.toByteArray())
    }

    /** The codes of the enabled cheats, ready for the core. */
    fun enabledCodes(): List<String> = load().filter { it.enabled }.map { it.coreCode }
}

object CheatCodes {
    /**
     * Cleans up a pasted code into lines, or throws IllegalArgumentException with
     * a message for the user. Accepts any spacing, newlines, "+" or "-" between groups.
     *
     * GBA (GameShark / Action Replay / CodeBreaker): lines of 8+8 or 8+4 hex digits.
     * DS (Action Replay): lines of 8+8 hex digits.
     */
    fun parse(text: String, platform: Platform): List<String> {
        val tokens = text.uppercase().split(Regex("[\\s+\\-:]+")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "Enter a code" }
        tokens.firstOrNull { t -> !t.all { it in '0'..'9' || it in 'A'..'F' } }?.let {
            throw IllegalArgumentException("\"$it\" isn't part of a code (codes use only 0-9 and A-F)")
        }
        val lines = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val first = tokens[i]
            require(first.length == 8) { "Each line should start with 8 digits; \"$first\" has ${first.length}" }
            val second = tokens.getOrNull(i + 1)
                ?: throw IllegalArgumentException("The last line ($first) is missing its second half")
            val okLengths = if (platform == Platform.NDS) setOf(8) else setOf(8, 4)
            require(second.length in okLengths) {
                if (platform == Platform.NDS) "DS codes are lines of 8 + 8 digits; \"$second\" has ${second.length}"
                else "GBA codes are lines of 8 + 8 or 8 + 4 digits; \"$second\" has ${second.length}"
            }
            lines += "$first $second"
            i += 2
        }
        return lines
    }
}
