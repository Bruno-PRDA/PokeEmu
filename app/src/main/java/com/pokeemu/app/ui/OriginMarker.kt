package com.pokeemu.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokeemu.core.Mon
import com.pokeemu.core.OriginLedger
import com.pokeemu.core.Transfer
import java.io.File

/** Where Pokémon come from: the record kept by the global PC plus each Pokémon's own game-of-origin byte. */
class Origins(context: Context) {
    private val ledger = OriginLedger(File(context.filesDir, "global-pc/origins.txt"))

    /** e.g. "Odyssey v4.1 (FireRed version)"; null if nothing is known. */
    fun describe(mon: Mon, currentGame: String? = null, fileExtension: String? = null): String? =
        ledger.describe(mon, currentGame, fileExtension)

    /** True if [mon] came from a game other than [gameName] (whose Pokémon use [fileExtension]). */
    fun isForeign(mon: Mon, gameName: String, fileExtension: String): Boolean {
        val recorded = ledger.get(mon)
        if (recorded != null) return Transfer.normalize(recorded) != Transfer.normalize(gameName)
        // No record: a GBA game of origin inside a DS game still gives it away.
        return (fileExtension == "pk4" && mon.originVersion in GBA_VERSIONS) ||
            (fileExtension == "pk5" && (mon.originVersion in GBA_VERSIONS || mon.originVersion in GEN4_VERSIONS))
    }

    private companion object {
        val GBA_VERSIONS = setOf(1, 2, 3, 4, 5)
        val GEN4_VERSIONS = setOf(7, 8, 10, 11, 12)
    }
}

/** Small dot marking a Pokémon that came from another game. */
@Composable
fun ForeignDot(modifier: Modifier = Modifier) =
    Box(modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
