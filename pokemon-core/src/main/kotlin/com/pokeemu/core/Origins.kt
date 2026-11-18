package com.pokeemu.core

import java.io.File

/** Game-of-origin ids stored in Gen 3 and Gen 4 Pokémon (the same numbering in both). */
object GameVersions {
    private val names = mapOf(
        1 to "Sapphire", 2 to "Ruby", 3 to "Emerald", 4 to "FireRed", 5 to "LeafGreen",
        7 to "HeartGold", 8 to "SoulSilver", 10 to "Diamond", 11 to "Pearl", 12 to "Platinum",
        15 to "Colosseum / XD",
        20 to "White", 21 to "Black", 22 to "White 2", 23 to "Black 2",
    )

    fun name(id: Int): String? = names[id]

    /** True if a game of origin [id] belongs to the generation whose Pokémon files are [fileExtension]. */
    fun isNative(id: Int, fileExtension: String): Boolean = when (fileExtension) {
        "pk3" -> id in 1..5 || id == 15
        "pk4" -> id in setOf(7, 8, 10, 11, 12)
        "pk5" -> id in 20..23
        else -> false
    }
}

/**
 * Which game file a Pokémon really comes from. A Pokémon only stores its base
 * game (an Odyssey Pokémon says "FireRed"), so the app notes the game the
 * first time a Pokémon passes through the global PC. Personality and trainer
 * IDs identify it in every game, so the record follows it across moves.
 *
 * Stored as lines of `personality-tid-sid|game name`.
 */
class OriginLedger(private val file: File) {
    private fun key(mon: Mon) = "%08x-%04x-%04x".format(mon.personality, mon.trainerId, mon.secretId)

    private fun load(): MutableMap<String, String> = runCatching {
        file.readLines().mapNotNull { line ->
            line.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap(LinkedHashMap())
    }.getOrDefault(LinkedHashMap())

    fun get(mon: Mon): String? = load()[key(mon)]

    /** Notes [game] as [mon]'s origin, unless it already has one. Returns the origin on record. */
    fun record(mon: Mon, game: String): String {
        val map = load()
        map[key(mon)]?.let { return it }
        map[key(mon)] = game.replace(Regex("[|\\r\\n]+"), " ").trim()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(map.entries.joinToString("") { "${it.key}|${it.value}\n" })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return map.getValue(key(mon))
    }

    /**
     * "Odyssey v4.1 (FireRed version)", "FireRed version", or null if nothing is known.
     * With no record, a Pokémon whose game of origin is of the generation it's being
     * viewed in ([fileExtension]) was caught in a game like [currentGame]: a hack
     * only stores its base game, so name the hack it's in.
     */
    fun describe(mon: Mon, currentGame: String? = null, fileExtension: String? = null): String? {
        val version = GameVersions.name(mon.originVersion)?.let { "$it version" }
        val game = get(mon) ?: currentGame?.takeIf { fileExtension != null && GameVersions.isNative(mon.originVersion, fileExtension) }
        return when {
            game != null && version != null && !game.contains(version.removeSuffix(" version"), ignoreCase = true) -> "$game ($version)"
            game != null -> game
            else -> version
        }
    }
}
