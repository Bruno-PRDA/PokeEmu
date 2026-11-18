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

    fun forget(romId: String) {
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
