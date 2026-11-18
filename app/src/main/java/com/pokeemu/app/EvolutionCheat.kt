package com.pokeemu.app

import android.content.Context
import com.pokeemu.core.EvoFix
import com.pokeemu.gen3.Gen3Evolutions
import com.pokeemu.gen4.FileRomSource
import com.pokeemu.gen4.Gen4Evolutions
import com.pokeemu.saves.GameStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The built-in "Evolve without trading" cheat: which evolution data to change
 * (see Gen3Evolutions / Gen4Evolutions). The changes are applied to the game's
 * data in memory when it starts; the ROM file is never written.
 */
object EvolutionCheat {
    /** The changes for this game, each with a description ("Kadabra → Alakazam: level 36"). */
    suspend fun changes(context: Context, rom: File, romId: String, ds: Boolean): List<Pair<EvoFix, String>> = withContext(Dispatchers.IO) {
        val store = RomDataStore(GameStorage(context.filesDir))
        val gen5 = ds && com.pokeemu.saves.NdsHeader.parse(rom.inputStream().use { i -> ByteArray(com.pokeemu.saves.NdsHeader.HEADER_SIZE).also { i.read(it) } })?.pokemonGen == 5
        if (gen5) {
            val data = store.gen5(romId, rom) ?: return@withContext emptyList()
            FileRomSource(rom).use { com.pokeemu.gen5.Gen5Evolutions.tradeFixes(it) { id -> data.itemName(id) } }
                .map { it to "${data.speciesName(it.species)} → ${data.speciesName(it.target)}: ${it.how}" }
        } else if (ds) {
            val data = store.gen4(romId, rom) ?: return@withContext emptyList()
            FileRomSource(rom).use { Gen4Evolutions.tradeFixes(it, data.itemNames, data::speciesName) }
                .map { it to "${data.speciesName(it.species)} → ${data.speciesName(it.target)}: ${it.how}" }
        } else {
            val bytes = rom.readBytes() // GBA ROMs are at most 32 MB
            val data = store.cached(romId) ?: store.scanAndCache(romId, bytes)
            val (table, stride) = Gen3Evolutions.locate(bytes, Gen3Evolutions.referenced(bytes), data.speciesNames.size.coerceAtLeast(412))
                ?: return@withContext emptyList()
            // FireRed/LeafGreen have no way to raise beauty (Feebas).
            val frlg = String(bytes, 0xAC, 3, Charsets.ISO_8859_1) in setOf("BPR", "BPG")
            Gen3Evolutions.tradeFixes(bytes, table, stride, data.speciesNames.size, fixBeauty = frlg, itemName = data::itemName)
                .map { it to "${data.speciesName(it.species)} → ${data.speciesName(it.target)}: ${it.how}" }
        }
    }
}
