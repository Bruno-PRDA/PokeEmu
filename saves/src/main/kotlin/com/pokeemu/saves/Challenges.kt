package com.pokeemu.saves

import java.io.File
import java.util.Properties

/**
 * Challenge settings and the Nuzlocke log of one save slot, stored in
 * games/<romId>/challenges/<slot>/. They only change how the running game
 * behaves (its memory); the ROM and the save file are never edited.
 */
class ChallengeStore(storage: GameStorage, romId: String, val slot: String = storage.activeSlot(romId)) {
    private val dir = File(storage.gameDir(romId), "challenges/${slot.replace(Regex("[^A-Za-z0-9 _-]"), "_")}")
    private val settingsFile = File(dir, "settings.properties")
    private val logFile = File(dir, "log.txt")

    enum class Kind { ENCOUNTER, JOINED, DEATH }
    data class Entry(val time: Long, val kind: Kind, val pid: Long, val text: String)

    private fun settings() = Properties().apply { if (settingsFile.isFile) settingsFile.inputStream().use(::load) }
    private fun save(p: Properties) { dir.mkdirs(); settingsFile.outputStream().use { p.store(it, null) } }

    /** Shiny odds: 0 = the game's own, 1 = every wild Pokémon, N = 1 in N. */
    var shinyOdds: Int
        get() = settings().getProperty("shinyOdds")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(v) = save(settings().apply { setProperty("shinyOdds", v.toString()) })

    var nuzlocke: Boolean
        get() = settings().getProperty("nuzlocke") == "true"
        set(v) = save(settings().apply { setProperty("nuzlocke", v.toString()) })

    val active get() = shinyOdds != 0 || nuzlocke

    fun log(): List<Entry> = runCatching {
        logFile.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size != 4) return@mapNotNull null
            Entry(p[0].toLongOrNull() ?: 0, Kind.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null, p[2].toLongOrNull() ?: 0, p[3])
        }
    }.getOrDefault(emptyList())

    fun add(kind: Kind, pid: Long, text: String, time: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        logFile.appendText("$time|${kind.name}|$pid|${text.replace(Regex("[|\\r\\n]+"), " ")}\n")
    }

    /** Pokémon (by personality value) that fainted under the Nuzlocke rules. */
    fun dead(): Set<Long> = log().filter { it.kind == Kind.DEATH }.map { it.pid }.toSet()

    fun clearLog() { logFile.delete() }
}
