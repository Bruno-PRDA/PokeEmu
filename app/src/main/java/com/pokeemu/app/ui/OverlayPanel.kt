package com.pokeemu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.pokeemu.gen3.GameRules
import com.pokeemu.gen3.Gender
import com.pokeemu.gen3.LiveState
import com.pokeemu.gen3.Pk3
import com.pokeemu.gen3.RomData

private val Dim = Color.White.copy(alpha = 0.65f)
private val IV_LABELS = listOf("HP", "Atk", "Def", "Spe", "SpA", "SpD")

/**
 * Read-only battle info from game RAM. [state] null = party not found yet;
 * [rom] null = game data not loaded yet (numbers are shown instead of names).
 */
@Composable
fun OverlayPanel(state: LiveState?, rom: RomData?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0xE6101418), RoundedCornerShape(12.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) {
            Text("Looking for your party in the game's memory…", color = Dim, fontSize = 13.sp)
            return@Column
        }
        if (state.opponents.isNotEmpty()) {
            SectionLabel("Opponent")
            state.opponents.forEach { OpponentCard(it, rom) }
        } else {
            Text("No opponent — start a battle to see its stats.", color = Dim, fontSize = 13.sp)
        }
        SectionLabel("Your party")
        state.party.forEach { PartyRow(it, rom) }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text.uppercase(), color = Color(0xFFEF9A9A), fontSize = 11.sp, fontWeight = FontWeight.Bold)

private fun name(pk: Pk3, rom: RomData?) = when {
    pk.isEgg -> "Egg"
    rom != null -> rom.speciesName(pk.species)
    else -> "#${pk.species}"
}

private fun genderSymbol(pk: Pk3, rom: RomData?): String {
    val ratio = rom?.base(pk.species)?.genderRatio ?: return ""
    return when (GameRules.gender(pk.personality, ratio)) {
        Gender.MALE -> " ♂"
        Gender.FEMALE -> " ♀"
        Gender.GENDERLESS -> ""
    }
}

@Composable
private fun OpponentCard(pk: Pk3, rom: RomData?) {
    val base = rom?.base(pk.species)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name(pk, rom) + genderSymbol(pk, rom) + if (pk.isShiny) " ★" else "",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f),
            )
            Text("Lv${pk.level}", color = Color.White, fontSize = 14.sp)
        }
        HpBar(pk)
        val ability = base?.let { if (pk.abilitySlot == 1 && it.ability2 != 0) it.ability2 else it.ability1 }
        Text(
            listOfNotNull(
                Pk3.NATURES[pk.nature],
                ability?.let { rom.abilityName(it) },
                base?.let { RomData.typeName(it.type1) + if (it.type2 != it.type1) "/" + RomData.typeName(it.type2) else "" },
            ).joinToString(" · "),
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
        val moves = pk.moves.filter { it != 0 }.map { rom?.moveName(it) ?: "#$it" }
        if (moves.isNotEmpty()) Text(moves.joinToString(" · "), color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun PartyRow(pk: Pk3, rom: RomData?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            name(pk, rom), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )
        Text("Lv${pk.level}", color = Dim, fontSize = 12.sp, modifier = Modifier.width(40.dp))
        Box(Modifier.weight(1f)) { HpBar(pk) }
    }
}

@Composable
private fun HpBar(pk: Pk3) {
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
