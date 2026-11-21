package com.pokeemu.saves

import java.io.File

/**
 * Values found with the cheat search and frozen: while the game runs, the
 * app keeps writing [value] ([size] bytes) at [address] in its memory. Kept
 * per game in games/<romId>/pins.txt; the ROM and save file are never changed.
 */
class PinStore(storage: GameStorage, romId: String) {
    private val file = File(storage.gameDir(romId), "pins.txt")

    data class Pin(val address: Long, val size: Int, val value: Long, val label: String, val enabled: Boolean = true)

    fun list(): List<Pin> = runCatching {
        file.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val p = line.split('|', limit = 5)
            if (p.size != 5) return@mapNotNull null
            Pin(p[0].toLongOrNull(16) ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toLongOrNull() ?: 0, p[4], p[3] == "1")
        }
    }.getOrDefault(emptyList())

    fun save(pins: List<Pin>) {
        file.parentFile?.mkdirs()
        file.writeText(pins.joinToString("") { "%X|%d|%d|%d|%s\n".format(it.address, it.size, it.value, if (it.enabled) 1 else 0, it.label.replace(Regex("[|\\r\\n]+"), " ")) })
    }

    /** Adds [pin], replacing one at the same address. */
    fun put(pin: Pin) = save(list().filter { it.address != pin.address } + pin)

    fun remove(address: Long) = save(list().filter { it.address != address })
}
