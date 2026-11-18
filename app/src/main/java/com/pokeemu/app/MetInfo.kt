package com.pokeemu.app

import android.content.Context
import com.pokeemu.core.Mon
import com.pokeemu.gen3.Pk3
import com.pokeemu.gen5.Pk5
import com.pokeemu.saves.GameStorage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where and when a Pokémon was met, named from its game's ROM. */
object MetInfo {
    /** Location number -> place name for [rom] (read once, then cached). */
    suspend fun places(context: Context, rom: RomEntry): Map<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            val local = RomLibrary(context).localCopy(rom.uri)
            RomDataStore(GameStorage(context.filesDir)).locations(local.id, local.file, rom.header.pokemonGen ?: 3)
        }.getOrDefault(emptyMap())
    }

    /** "Route 1 · Lv 5 · Nov 18, 2026", "Hatched in Nuvema Town", or null if nothing is known. */
    fun describe(mon: Mon, places: Map<Int, String>): String? {
        val loc = mon.metLocation
        val place = when {
            mon is Pk3 && loc == 0xFF -> "In-game trade"
            mon is Pk3 && loc == 0xFE -> "Fateful encounter"
            mon is Pk5 && loc == 30001 -> "Poké Transfer"
            mon !is Pk3 && loc >= 2000 -> "Trade or event"
            else -> places[loc]?.takeIf { n -> n.any(Char::isLetter) }
        }
        val hatched = mon.metLevel == 0
        val parts = listOfNotNull(
            when {
                hatched && place != null -> "Hatched in $place"
                hatched -> "Hatched from an Egg"
                else -> place
            },
            mon.metLevel.takeIf { it > 0 }?.let { "Lv $it" },
            mon.metDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
