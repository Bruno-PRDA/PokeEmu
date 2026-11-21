package com.pokeemu.app

import com.pokeemu.gen3.RomData
import com.pokeemu.gen3.RomScanner
import com.pokeemu.gen4.FileRomSource
import com.pokeemu.gen4.Gen4RomData
import com.pokeemu.saves.GameStorage
import java.io.File

/** Scans a ROM once and caches the result next to the game's saves. */
class RomDataStore(private val storage: GameStorage) {

    private fun cacheFile(romId: String) = File(storage.gameDir(romId), "romdata.bin")

    /** Cached data, or null if this ROM hasn't been scanned (or the scanner changed). */
    fun cached(romId: String): RomData? =
        cacheFile(romId).takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use(RomData::read) }.getOrNull() }

    fun scanAndCache(romId: String, rom: ByteArray): RomData {
        val data = RomScanner(rom).scan()
        val tmp = File(cacheFile(romId).path + ".tmp")
        tmp.outputStream().use(data::write)
        tmp.renameTo(cacheFile(romId))
        return data
    }

    private companion object { const val LOCATIONS_VERSION = 1 }

    fun forget(romId: String) {
        File(storage.gameDir(romId), "locations$LOCATIONS_VERSION.txt").delete()
        cacheFile(romId).delete()
        gen4CacheFile(romId).delete()
        gen5CacheFile(romId).delete()
    }

    private fun gen5CacheFile(romId: String) = File(storage.gameDir(romId), "romdata5.bin")

    /** Game data read from a Black/White or Black 2/White 2 ROM, cached; null if it can't be read. */
    fun gen5(romId: String, romFile: File): com.pokeemu.gen5.Gen5RomData? {
        val cache = gen5CacheFile(romId)
        cache.takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use(com.pokeemu.gen5.Gen5RomData::read) }.getOrNull() }?.let { return it }
        val data = runCatching { FileRomSource(romFile).use(com.pokeemu.gen5.Gen5RomData::scan) }.getOrNull() ?: return null
        val tmp = File(cache.path + ".tmp")
        tmp.outputStream().use(data::write)
        tmp.renameTo(cache)
        return data
    }

    /**
     * Names of the places Pokémon are met in (location number -> name), read
     * from the ROM once and cached. [gen] is 3, 4 or 5. Empty if not found.
     */
    fun locations(romId: String, romFile: File, gen: Int): Map<Int, String> {
        val cache = File(storage.gameDir(romId), "locations$LOCATIONS_VERSION.txt")
        if (cache.isFile) runCatching {
            return cache.readLines().mapNotNull { l -> l.split('|', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.toIntOrNull()?.let { it to v } } }.toMap()
        }
        val names = runCatching {
            when (gen) {
                3 -> com.pokeemu.gen3.Gen3Locations.read(romFile.readBytes())
                4 -> FileRomSource(romFile).use(com.pokeemu.gen4.Gen4Locations::read)
                5 -> FileRomSource(romFile).use(com.pokeemu.gen5.Gen5Locations::read)
                else -> emptyMap()
            }
        }.getOrDefault(emptyMap())
        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeText(names.entries.joinToString("") { "${it.key}|${it.value.replace(Regex("[|\r\n]+"), " ")}\n" })
        }
        return names
    }

    private fun gen4CacheFile(romId: String) = File(storage.gameDir(romId), "romdata4.bin")

    /** Names read from a DS ROM, cached; null if the ROM has no recognisable Gen 4 text banks. */
    fun gen4(romId: String, romFile: File): Gen4RomData? {
        val cache = gen4CacheFile(romId)
        cache.takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use(Gen4RomData::read) }.getOrNull() }?.let { return it }
        val data = runCatching { FileRomSource(romFile).use(Gen4RomData::scan) }.getOrNull() ?: return null
        val tmp = File(cache.path + ".tmp")
        tmp.outputStream().use(data::write)
        tmp.renameTo(cache)
        return data
    }
}
