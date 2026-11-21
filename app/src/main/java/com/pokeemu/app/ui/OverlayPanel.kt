package com.pokeemu.app.ui

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.core.GameData
import com.pokeemu.core.LiveState
import com.pokeemu.core.Mon

private val Dim = Color.White.copy(alpha = 0.65f)
private val IV_LABELS = listOf("HP", "Atk", "Def", "Spe", "SpA", "SpD")

/**
 * Read-only battle info from game RAM. [state] null = party not found yet;
 * [rom] null = game data not loaded yet (numbers are shown instead of names).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OverlayPanel(state: LiveState?, rom: GameData?, modifier: Modifier = Modifier, sprites: com.pokeemu.core.SpriteSource? = null) {
    // Only the opponent is shown; outside battle the panel stays out of the way.
    val opponents = state?.opponents.orEmpty()
    if (opponents.isEmpty()) return
    Column(
        modifier
            .background(Color(0xE6101418), RoundedCornerShape(12.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel("Opponent")
        opponents.forEach { OpponentCard(it, rom, sprites) }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text.uppercase(), color = Color(0xFFEF9A9A), fontSize = 11.sp, fontWeight = FontWeight.Bold)

private fun name(pk: Mon, rom: GameData?) = when {
    pk.isEgg -> "Egg"
    rom != null -> rom.speciesName(pk.species)
    else -> "#${pk.species}"
}

private fun genderSymbol(pk: Mon, rom: GameData?): String {
    val ratio = rom?.base(pk.species)?.genderRatio ?: return ""
    return when (GameRules.gender(pk.personality, ratio)) {
        Gender.MALE -> " ♂"
        Gender.FEMALE -> " ♀"
        Gender.GENDERLESS -> ""
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OpponentCard(pk: Mon, rom: GameData?, sprites: com.pokeemu.core.SpriteSource?) {
    val base = rom?.base(pk.species)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(52.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { MonSprite(sprites, if (pk.isEgg) 0 else pk.species, pk.isShiny, 48.dp) }
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name(pk, rom) + genderSymbol(pk, rom) + if (pk.isShiny) " ★" else "",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f),
                    )
                    Text("Lv${pk.level}", color = Color.White, fontSize = 14.sp)
                }
                HpBar(pk)
                if (base != null && rom != null) TypeChips(listOf(rom.typeName(base.type1), rom.typeName(base.type2)), compact = true)
            }
        }
        val ability = pk.abilityId(base)
        Text(
            listOfNotNull(GameData.NATURES[pk.nature], ability?.let { rom?.abilityName(it) }).joinToString(" · "),
            color = Dim, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pk.ivs.forEachIndexed { i, iv ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(IV_LABELS[i], color = Dim, fontSize = 10.sp)
                    Text(
                        "$iv", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = when { iv == 31 -> Color(0xFF81C784); iv <= 5 -> Color(0xFFE57373); else -> Color.White },
                    )
                }
            }
        }
        val moves = pk.moves.filter { it != 0 }
        if (moves.isNotEmpty()) {
            // Moves as chips tinted by type.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (m in moves) {
                    val type = rom?.moveInfo(m)?.type?.let { rom.typeName(it) }
                    Text(
                        rom?.moveName(m) ?: "#$m",
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier
                            .background((type?.let(::typeColor) ?: Color.Gray).copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HpBar(pk: Mon) {
    val hp = pk.currentHp ?: 0
    val max = (pk.maxHp ?: 1).coerceAtLeast(1)
    val fraction = hp.toFloat() / max
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            color = when { fraction > 0.5f -> Color(0xFF66BB6A); fraction > 0.2f -> Color(0xFFFFCA28); else -> Color(0xFFEF5350) },
            trackColor = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.weight(1f).height(6.dp),
        )
        Text("$hp/$max", color = Dim, fontSize = 11.sp)
    }
}
