package com.pokeemu.app.ui

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pokeemu.core.GameData
import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor

private val STAT_NAMES = listOf("HP", "Attack", "Defense", "Speed", "Sp. Atk", "Sp. Def")

/**
 * Full-screen editor for one Pokémon. Edits a private copy; [onDone] hands
 * the edited copy back, [onCancel] discards it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PokemonDetail(
    original: Mon,
    editor: MonEditor,
    onDone: (Mon) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val pk = remember(original) { original.copy() }
    var tick by remember { mutableIntStateOf(0) } // bump after mutating pk to recompose
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val rom = editor.data

    fun edit(block: () -> Unit) {
        try {
            block(); error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
        tick++
    }

    tick.let { } // the title also depends on edits
    val title = if (pk.isEgg) "Egg" else pk.nickname.ifBlank { rom.speciesName(pk.species) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Discard changes") } },
                actions = { TextButton(onClick = { onDone(pk) }) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Summary", "Moves", "Stats").forEachIndexed { i, t -> Tab(tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val warnings = editor.warnings(pk)
                if (warnings.isNotEmpty() || error != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
                            warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.onErrorContainer) }
                        }
                    }
                }
                tick.let { } // re-run this block (and the non-skippable tabs) after every edit
                when (tab) {
                    0 -> SummaryTab(pk, editor, rom, ::edit)
                    1 -> MovesTab(pk, editor, rom, ::edit)
                    else -> StatsTab(pk, editor, ::edit)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport) { Text("Export .${editor.fileExtension}") }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@NonSkippableComposable // reads `pk`, which is edited in place
@Composable
private fun SummaryTab(pk: Mon, editor: MonEditor, rom: GameData, edit: (() -> Unit) -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    val base = rom.base(pk.species)

    LabeledButton("Species", rom.speciesName(pk.species)) { picker = "species" }

    var nick by remember(pk.personality, pk.species) { mutableStateOf(pk.nickname) }
    OutlinedTextField(
        nick, { v ->
            nick = v.take(editor.maxNicknameLength)
            if (editor.canEncode(nick)) edit { editor.setNickname(pk, nick) }
        },
        label = { Text("Nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = !editor.canEncode(nick),
        supportingText = { if (!editor.canEncode(nick)) Text("Contains a character this game can't show") },
    )

    val level = editor.level(pk)
    NumberField("Level", level ?: 0, 1..100, enabled = base != null) { v -> edit { editor.setLevel(pk, v) } }
    if (base == null) Text("Level can't be changed: this species' growth rate wasn't found in the ROM.", style = MaterialTheme.typography.bodySmall)
    Text("Experience: ${pk.experience}", style = MaterialTheme.typography.bodySmall)

    LabeledButton("Held item", rom.itemName(pk.heldItem)) { picker = "item" }

    Text("Nature", style = MaterialTheme.typography.labelLarge)
    Dropdown(GameData.NATURES[pk.nature], GameData.NATURES) { n ->
        edit { editor.setIdentity(pk, n, editor.gender(pk), pk.isShiny) }
    }

    val ratio = base?.genderRatio
    val gender = editor.gender(pk)
    if (ratio != null && ratio !in setOf(0, 254, 255)) {
        Text("Gender", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in listOf(Gender.MALE, Gender.FEMALE)) {
                FilterChip(selected = gender == g, onClick = { edit { editor.setIdentity(pk, pk.nature, g, pk.isShiny) } },
                    label = { Text(if (g == Gender.MALE) "♂ Male" else "♀ Female") })
            }
        }
    } else if (gender != null) {
        Text("Gender: ${gender.name.lowercase().replaceFirstChar { it.uppercase() }} (fixed for this species)")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Shiny", Modifier.weight(1f))
        Switch(checked = pk.isShiny, onCheckedChange = { s -> edit { editor.setIdentity(pk, pk.nature, editor.gender(pk), s) } })
    }

    if (base != null) {
        Text("Ability", style = MaterialTheme.typography.labelLarge)
        val current = editor.abilityIndex(pk)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            editor.abilityOptions(pk).forEachIndexed { i, a ->
                FilterChip(current == i, onClick = { edit { editor.setAbility(pk, i) } }, label = { Text(rom.abilityName(a)) })
            }
        }
        if (current < 0) Text("This game stores abilities its own way, so the ability is left unchanged.", style = MaterialTheme.typography.bodySmall)
        Text("Type: ${rom.typeName(base.type1)}${if (base.type2 != base.type1) " / ${rom.typeName(base.type2)}" else ""}")
    }

    Text("Friendship: ${pk.friendship}", style = MaterialTheme.typography.labelLarge)
    Slider(pk.friendship.toFloat(), { v -> edit { pk.friendship = v.toInt() } }, valueRange = 0f..255f)

    Text(
        "OT ${pk.otName} · ID %05d · Personality %08X".format(pk.trainerId, pk.personality),
        style = MaterialTheme.typography.bodySmall,
    )

    when (picker) {
        "species" -> IdPicker("Species", rom.speciesNames, allowZero = false, onPick = { id -> picker = null; edit { editor.setSpecies(pk, id) } }, onDismiss = { picker = null })
        "item" -> IdPicker("Held item", rom.itemNames, allowZero = true, onPick = { id -> picker = null; edit { pk.heldItem = id } }, onDismiss = { picker = null })
    }
}

@NonSkippableComposable
@Composable
private fun MovesTab(pk: Mon, editor: MonEditor, rom: GameData, edit: (() -> Unit) -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) }
    val learnable = remember(rom, pk.species) { rom.learnsets?.learnable(pk.species).orEmpty() }
    for (i in 0 until 4) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledButton("Move ${i + 1}", rom.moveName(pk.move(i))) { picking = i }
                if (pk.move(i) != 0 && learnable.isNotEmpty() && pk.move(i) !in learnable) {
                    Text(
                        "${rom.speciesName(pk.species)} can't normally learn this in this game",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (pk.move(i) != 0) {
                    val ups = pk.ppUps(i)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("PP", pk.pp(i), 0..99, modifier = Modifier.width(110.dp)) { v -> edit { pk.setPp(i, v) } }
                        Text("PP Ups")
                        for (u in 0..3) FilterChip(ups == u, onClick = {
                            edit { pk.setPpUps(i, u) }
                        }, label = { Text("$u") })
                    }
                }
            }
        }
    }
    picking?.let { i ->
        MovePicker(
            "Move ${i + 1}", rom, pk.species, known = pk.moves,
            onPick = { id -> picking = null; edit { editor.setMove(pk, i, id) } },
            onDismiss = { picking = null },
        )
    }
}

@NonSkippableComposable
@Composable
private fun StatsTab(pk: Mon, editor: MonEditor, edit: (() -> Unit) -> Unit) {
    val base = editor.data.base(pk.species)
    val level = editor.level(pk)
    val stats = if (base != null && level != null) GameRules.stats(base, level, pk.ivs, pk.evs, pk.nature) else null
    val evTotal = pk.evs.sum()
    Text("EVs used: $evTotal / ${GameRules.MAX_EV_TOTAL}", fontWeight = FontWeight.Bold,
        color = if (evTotal > GameRules.MAX_EV_TOTAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { edit { for (s in 0 until 6) pk.setIv(s, 31) } }) { Text("Max IVs") }
        OutlinedButton(onClick = { edit { for (s in 0 until 6) pk.setEv(s, 0) } }) { Text("Clear EVs") }
    }
    for (s in 0 until 6) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    val mod = GameRules.natureMultiplierTenths(pk.nature, s)
                    Text(STAT_NAMES[s] + when (mod) { 11 -> " ▲"; 9 -> " ▼"; else -> "" }, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    stats?.let { Text("${it[s]}", fontWeight = FontWeight.Bold) }
                }
                Text("IV ${pk.iv(s)}")
                Slider(pk.iv(s).toFloat(), { v -> edit { pk.setIv(s, v.toInt()) } }, valueRange = 0f..31f, steps = 30)
                Text("EV ${pk.ev(s)}")
                Slider(pk.ev(s).toFloat(), { v -> edit { pk.setEv(s, v.toInt()) } }, valueRange = 0f..255f)
            }
        }
    }
    if (stats == null) Text("Calculated stats need base stats from the ROM, which weren't found.", style = MaterialTheme.typography.bodySmall)
}

// ---- small widgets -------------------------------------------------------------------------

@Composable
private fun LabeledButton(label: String, value: String, onClick: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(value) }
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t ->
            text = t.filter(Char::isDigit).take(7)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) }, singleLine = true, enabled = enabled, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
    )
}

@Composable
private fun Dropdown(current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}
