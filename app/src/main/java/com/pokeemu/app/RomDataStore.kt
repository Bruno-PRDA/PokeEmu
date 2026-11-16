package com.pokeemu.app

import com.pokeemu.gen3.RomData
import com.pokeemu.gen3.RomScanner
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
    }
}
