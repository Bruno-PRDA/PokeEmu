@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.pokeemu.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pokeemu.core.GameData
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.core.SpriteSource

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
    /** Where it comes from, e.g. "Odyssey v4.1 (FireRed version)". */
    origin: String? = null,
    /** Sprites and item icons from the game's ROM. */
    sprites: SpriteSource? = null,
    /** Where and when it was met, e.g. "Route 1 · Lv 5 · Nov 18, 2026". */
    met: String? = null,
) {
    val pk = remember(original) { original.copy() }
    var tick by remember { mutableIntStateOf(0) } // bump after mutating pk to recompose
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val rom = editor.data
    var renaming by remember { mutableStateOf(false) }
    var pickSpecies by remember { mutableStateOf(false) }

    fun edit(block: () -> Unit) {
        try {
            block(); error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
        tick++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Pokémon") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Discard changes") } },
                actions = { TextButton(onClick = { onDone(pk) }) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            tick.let { } // the header also depends on edits
            Header(pk, editor, sprites, onRename = { if (!pk.isEgg) renaming = true }, onSpecies = { pickSpecies = true })
            TabRow(selectedTabIndex = tab) {
                listOf("Summary", "Moves", "Stats").forEachIndexed { i, t -> Tab(tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    0 -> SummaryTab(pk, editor, rom, origin, met, sprites, ::edit)
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

    if (renaming) {
        var nick by remember { mutableStateOf(pk.nickname) }
        val ok = editor.canEncode(nick)
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nickname") },
            text = {
                OutlinedTextField(
                    nick, { v -> nick = v.take(editor.maxNicknameLength) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), isError = !ok,
                    supportingText = {
                        Text(if (!ok) "Contains a character this game can't show" else "Up to ${editor.maxNicknameLength} characters · leave empty for the species name")
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    renaming = false
                    edit { editor.setNickname(pk, nick.ifBlank { rom.speciesName(pk.species).take(editor.maxNicknameLength) }) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (pickSpecies) IdPicker(
        "Species", rom.speciesNames, allowZero = false,
        onPick = { id -> pickSpecies = false; edit { editor.setSpecies(pk, id) } }, onDismiss = { pickSpecies = false },
        leading = { id -> MonSprite(sprites, id, false, 40.dp) },
        // Only species this game really has (hacks keep named slots with no data).
        allowed = { id -> com.pokeemu.core.Transfer.isRealSpecies(rom, id) },
    )
}

/** Sprite, name, species, level and types: who this is at a glance. Hold the name or species to change it. */
@NonSkippableComposable
@Composable
private fun Header(pk: Mon, editor: MonEditor, sprites: SpriteSource?, onRename: () -> Unit, onSpecies: () -> Unit) {
    val rom = editor.data
    val base = rom.base(pk.species)
    val species = rom.speciesName(pk.species)
    val name = if (pk.isEgg) "Egg" else pk.nickname.ifBlank { species }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SpriteTile(96.dp) { MonSprite(sprites, pk.species, pk.isShiny, 88.dp) }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.combinedClickable(onClick = {}, onLongClick = onRename), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pk.isShiny) Text("  ★", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
                }
                val gender = when (editor.gender(pk)) { Gender.MALE -> " ♂"; Gender.FEMALE -> " ♀"; else -> "" }
                Text(
                    listOfNotNull(species, editor.level(pk)?.let { "Lv $it" }).joinToString(" · ") + gender,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onSpecies),
                )
                if (base != null) TypeChips(listOf(rom.typeName(base.type1), rom.typeName(base.type2)))
            }
        }
    }
}

/** A titled group of fields. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@NonSkippableComposable // reads `pk`, which is edited in place
@Composable
private fun SummaryTab(pk: Mon, editor: MonEditor, rom: GameData, origin: String?, met: String?, sprites: SpriteSource?, edit: (() -> Unit) -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    val base = rom.base(pk.species)

    Section("Pokémon") {
        Text("Hold the name or species at the top to change them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Nature", style = MaterialTheme.typography.labelLarge)
        Dropdown(GameData.NATURES[pk.nature], GameData.NATURES) { n ->
            edit { editor.setIdentity(pk, n, editor.gender(pk), pk.isShiny) }
        }

        val ratio = base?.genderRatio
        val gender = editor.gender(pk)
        if (ratio != null && ratio !in setOf(0, 254, 255)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Gender", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                for (g in listOf(Gender.MALE, Gender.FEMALE)) {
                    FilterChip(selected = gender == g, onClick = { edit { editor.setIdentity(pk, pk.nature, g, pk.isShiny) } },
                        label = { Text(if (g == Gender.MALE) "♂ Male" else "♀ Female") })
                }
            }
        } else if (gender != null) {
            Text("Gender: ${gender.name.lowercase().replaceFirstChar { it.uppercase() }} (fixed for this species)", style = MaterialTheme.typography.bodyMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shiny", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
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
        }
    }

    Section("Level & item") {
        val level = editor.level(pk)
        NumberField("Level", level ?: 0, 1..100, enabled = base != null) { v -> edit { editor.setLevel(pk, v) } }
        if (base == null) Text("Level can't be changed: this species' growth rate wasn't found in the ROM.", style = MaterialTheme.typography.bodySmall)
        Text("Experience: ${pk.experience}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ValueRow("Held item", rom.itemName(pk.heldItem), leading = { ItemIcon(sprites, pk.heldItem, 32.dp) }) { picker = "item" }
    }

    Section("Trainer") {
        InfoRow("Original trainer", "${pk.otName} · ID %05d".format(pk.trainerId))
        InfoRow("Origin", origin ?: "Unknown")
        met?.let { InfoRow("Met", it) }
        InfoRow("Personality", "%08X".format(pk.personality))
        Text("Friendship: ${pk.friendship}", style = MaterialTheme.typography.labelLarge)
        Slider(pk.friendship.toFloat(), { v -> edit { pk.friendship = v.toInt() } }, valueRange = 0f..255f)
    }

    when (picker) {
        "species" -> IdPicker(
            "Species", rom.speciesNames, allowZero = false,
            onPick = { id -> picker = null; edit { editor.setSpecies(pk, id) } }, onDismiss = { picker = null },
            leading = { id -> MonSprite(sprites, id, false, 40.dp) },
            // Only species this game really has (hacks keep named slots with no data).
            allowed = { id -> com.pokeemu.core.Transfer.isRealSpecies(rom, id) },
        )
        "item" -> IdPicker(
            "Held item", rom.itemNames, allowZero = true,
            onPick = { id -> picker = null; edit { pk.heldItem = id } }, onDismiss = { picker = null },
            leading = { id -> ItemIcon(sprites, id, 32.dp) },
        )
    }
}

@NonSkippableComposable
@Composable
private fun MovesTab(pk: Mon, editor: MonEditor, rom: GameData, edit: (() -> Unit) -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) }
    val learnable = remember(rom, pk.species) { rom.learnsets?.learnable(pk.species).orEmpty() }
    for (i in 0 until 4) {
        val move = pk.move(i)
        val info = rom.moveInfo(move)
        ElevatedCard(Modifier.fillMaxWidth().clickable { picking = i }) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (move != 0 && info != null) {
                        Box(Modifier.width(4.dp).height(36.dp).background(typeColor(rom.typeName(info.type)), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (move == 0) "Empty slot" else rom.moveName(move),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (move == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        if (info != null) Text(info.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (move == 0) Text("Tap to choose a move", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (info != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TypeChip(rom.typeName(info.type))
                        CategoryBadge(info.category)
                    }
                }
                if (move != 0 && learnable.isNotEmpty() && move !in learnable) {
                    Text(
                        "${rom.speciesName(pk.species)} can't normally learn this in this game",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (move != 0) {
                    HorizontalDivider()
                    val ups = pk.ppUps(i)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField("PP", pk.pp(i), 0..99, modifier = Modifier.width(96.dp)) { v -> edit { pk.setPp(i, v) } }
                        Text("PP Ups", style = MaterialTheme.typography.labelMedium)
                        for (u in 0..3) FilterChip(ups == u, onClick = { edit { pk.setPpUps(i, u) } }, label = { Text("$u") })
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "EVs $evTotal / ${GameRules.MAX_EV_TOTAL}", Modifier.weight(1f), fontWeight = FontWeight.Bold,
            color = if (evTotal > GameRules.MAX_EV_TOTAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(onClick = { edit { for (s in 0 until 6) pk.setIv(s, 31) } }) { Text("Max IVs") }
        OutlinedButton(onClick = { edit { for (s in 0 until 6) pk.setEv(s, 0) } }) { Text("Clear EVs") }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (s in 0 until 6) {
                val mod = GameRules.natureMultiplierTenths(pk.nature, s)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(STAT_NAMES[s], Modifier.width(72.dp), fontWeight = FontWeight.Bold)
                        Text(
                            when (mod) { 11 -> "▲"; 9 -> "▼"; else -> "" },
                            Modifier.width(16.dp),
                            color = if (mod == 11) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        val value = stats?.get(s)
                        LinearProgressIndicator(
                            progress = { ((value ?: 0) / 400f).coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).height(8.dp),
                        )
                        Text(value?.toString() ?: "—", Modifier.width(44.dp).padding(start = 8.dp), fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("IV ${pk.iv(s)}", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                        Slider(pk.iv(s).toFloat(), { v -> edit { pk.setIv(s, v.toInt()) } }, valueRange = 0f..31f, steps = 30, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("EV ${pk.ev(s)}", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                        Slider(pk.ev(s).toFloat(), { v -> edit { pk.setEv(s, v.toInt()) } }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (stats == null) Text("Calculated stats need base stats from the ROM, which weren't found.", style = MaterialTheme.typography.bodySmall)
}

// ---- small widgets -------------------------------------------------------------------------

/** A tappable row: label above, value (with an optional picture) on the right. */
@Composable
private fun ValueRow(label: String, value: String, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            leading?.invoke()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
