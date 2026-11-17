package com.pokeemu.core

import java.io.File
import java.text.Normalizer
import java.util.Properties
import java.util.UUID

/**
 * A Pokémon in the global PC: its single-Pokémon file bytes (.pk3/.pk4) plus
 * the names of its species, moves and item as its own game called them, so it
 * can be matched up in another game whose ROM numbers them differently (hacks
 * reorder species and add new ones).
 */
data class StoredMon(
    val id: String,
    /** File extension of [bytes]: "pk3" (GBA) or "pk4" (DS). */
    val format: String,
    val bytes: ByteArray,
    val speciesName: String,
    val nickname: String,
    val level: Int?,
    val shiny: Boolean,
    val moveNames: List<String>,
    val itemName: String?,
    /** The game it was deposited from (display name) and that ROM's id. */
    val fromGame: String,
    val fromRomId: String,
    val storedAt: Long,
) {
    override fun equals(other: Any?) = other is StoredMon && other.id == id
    override fun hashCode() = id.hashCode()
}

/** The global PC: Pokémon stored outside any game, one pair of files each in [dir]. */
class GlobalPc(private val dir: File) {

    fun list(): List<StoredMon> =
        dir.listFiles { f -> f.extension == "properties" }.orEmpty().mapNotNull(::read).sortedBy { it.storedAt }

    /** Stores [mon] from [editor]'s game. Returns the stored entry. */
    fun deposit(editor: MonEditor, mon: Mon, gameName: String, romId: String): StoredMon {
        requireCanDeposit(mon)
        val data = editor.data
        val stored = StoredMon(
            id = UUID.randomUUID().toString(),
            format = editor.fileExtension,
            bytes = editor.exportBytes(mon),
            speciesName = data.speciesName(mon.species),
            nickname = mon.nickname,
            level = editor.level(mon),
            shiny = mon.isShiny,
            moveNames = mon.moves.filter { it != 0 }.map(data::moveName),
            itemName = mon.heldItem.takeIf { it != 0 }?.let(data::itemName),
            fromGame = gameName,
            fromRomId = romId,
            storedAt = System.currentTimeMillis(),
        )
        write(stored)
        return stored
    }

    /** Damaged records (bad checksum, e.g. leftovers in a hack's unused box space) stay where they are. */
    fun requireCanDeposit(mon: Mon) {
        require(mon.checksumValid && mon.species != 0) { "This Pokémon's data is damaged, so it can't be moved" }
    }

    fun remove(stored: StoredMon) {
        File(dir, "${stored.id}.bin").delete()
        File(dir, "${stored.id}.properties").delete()
    }

    private fun write(s: StoredMon) {
        dir.mkdirs()
        File(dir, "${s.id}.bin").writeBytes(s.bytes)
        val p = Properties()
        p["format"] = s.format
        p["species"] = s.speciesName
        p["nickname"] = s.nickname
        s.level?.let { p["level"] = it.toString() }
        p["shiny"] = s.shiny.toString()
        p["moves"] = s.moveNames.joinToString("\n")
        s.itemName?.let { p["item"] = it }
        p["fromGame"] = s.fromGame
        p["fromRomId"] = s.fromRomId
        p["storedAt"] = s.storedAt.toString()
        // Written last: an entry only exists once both files are complete.
        val tmp = File(dir, "${s.id}.properties.tmp")
        tmp.outputStream().use { p.store(it, null) }
        check(tmp.renameTo(File(dir, "${s.id}.properties"))) { "Could not store the Pokémon" }
    }

    private fun read(meta: File): StoredMon? = runCatching {
        val id = meta.nameWithoutExtension
        val p = Properties().apply { meta.inputStream().use(::load) }
        StoredMon(
            id = id,
            format = p.getProperty("format"),
            bytes = File(dir, "$id.bin").readBytes(),
            speciesName = p.getProperty("species", "?"),
            nickname = p.getProperty("nickname", ""),
            level = p.getProperty("level")?.toIntOrNull(),
            shiny = p.getProperty("shiny") == "true",
            moveNames = p.getProperty("moves", "").split('\n').filter { it.isNotEmpty() },
            itemName = p.getProperty("item"),
            fromGame = p.getProperty("fromGame", ""),
            fromRomId = p.getProperty("fromRomId", ""),
            storedAt = p.getProperty("storedAt")?.toLongOrNull() ?: meta.lastModified(),
        )
    }.getOrNull()
}

/**
 * Turns a stored Pokémon into one that fits the game [editor] is editing.
 * In the same ROM nothing changes. In another ROM the species, moves and held
 * item are looked up by name, since hacks number them differently.
 */
object Transfer {
    /** A ready-to-insert Pokémon, plus notes on anything that had to change. */
    class Result(val mon: Mon, val notes: List<String>)

    /** Throws IllegalArgumentException (with a message for the user) if it can't go into this game. */
    fun adapt(stored: StoredMon, editor: MonEditor, targetRomId: String): Result {
        require(stored.format == editor.fileExtension) {
            val from = if (stored.format == "pk3") "GBA" else "DS"
            val to = if (editor.fileExtension == "pk3") "GBA" else "DS"
            "${stored.speciesName} is from a $from game; it can only go into $from games ($to games aren't supported yet)"
        }
        val mon = editor.parseImport(stored.bytes)
        if (stored.fromRomId == targetRomId) return Result(mon, emptyList())

        val data = editor.data
        val notes = mutableListOf<String>()

        // Species: must exist in the target game under the same name.
        val species = find(data.speciesNames, stored.speciesName)
        if (species == null) {
            require(data.speciesNames.isEmpty() || isNumbered(stored.speciesName)) {
                "${stored.speciesName} isn't in this game"
            }
            require(data.speciesNames.isEmpty() || mon.species < data.speciesNames.size) {
                "${stored.speciesName} isn't in this game"
            }
        } else if (species != mon.species) {
            mon.species = species
        }

        // Moves: matched by name; ones this game doesn't have are dropped.
        if (data.moveNames.isNotEmpty() && stored.moveNames.none(::isNumbered)) {
            val ups = (0 until 4).filter { mon.move(it) != 0 }.map(mon::ppUps)
            val kept = stored.moveNames.mapIndexedNotNull { i, name ->
                val id = find(data.moveNames, name)
                if (id == null) notes += "Forgot $name (not in this game)"
                id?.let { it to ups.getOrElse(i) { 0 } }
            }
            require(kept.isNotEmpty()) { "None of ${stored.speciesName}'s moves exist in this game" }
            for (i in 0 until 4) {
                val (move, up) = kept.getOrNull(i) ?: (0 to 0)
                mon.setPpUps(i, up)
                editor.setMove(mon, i, move)
            }
        }

        // Held item: matched by name, else removed.
        val item = stored.itemName
        if (item != null && data.itemNames.isNotEmpty() && !isNumbered(item)) {
            val id = find(data.itemNames, item)
            if (id == null) notes += "Left its $item behind (not in this game)"
            mon.heldItem = id ?: 0
        }

        // Ability: keep the same slot if the species has it, else its first ability.
        val options = runCatching { editor.abilityOptions(mon) }.getOrDefault(emptyList())
        if (options.isNotEmpty() && editor.abilityIndex(mon) < 0) editor.setAbility(mon, 0)

        return Result(mon, notes)
    }

    /** Index of [name] in [names], ignoring case, accents and punctuation; first match wins. */
    fun find(names: List<String>, name: String): Int? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        return names.indices.firstOrNull { it != 0 && normalize(names[it]) == key }
    }

    /** "#123" / "Move #5": a placeholder for a name the source ROM didn't have. */
    private fun isNumbered(name: String) = Regex("""(^|\s)#\d+$""").containsMatchIn(name)

    fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()
        .replace('♀', 'f').replace('♂', 'm')
        .filter { it.isLetterOrDigit() }
}
