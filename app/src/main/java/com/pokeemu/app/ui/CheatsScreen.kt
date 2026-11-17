package com.pokeemu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pokeemu.saves.Cheat
import com.pokeemu.saves.CheatCodes
import com.pokeemu.saves.CheatStore
import com.pokeemu.saves.Platform

/**
 * A game's cheat list: switch cheats on and off, add, edit and delete them.
 * Every change is saved at once; [onChanged] is called after each one (the
 * player uses it to re-send the enabled cheats to the running game).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatsScreen(
    title: String,
    store: CheatStore,
    platform: Platform,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
    /** The evolutions the built-in cheat changes in this game (loads the ROM's data). */
    evolutionChanges: (suspend () -> List<String>)? = null,
    /** True while the game is running (built-in cheats then apply from the next start). */
    inGame: Boolean = false,
) {
    var cheats by remember { mutableStateOf(store.load()) }
    var evolve by remember { mutableStateOf(store.evolveWithoutTrading) }
    var showChanges by remember { mutableStateOf(false) }
    /** Index being edited; -1 = adding a new one; null = no editor open. */
    var editing by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }

    fun update(list: List<Cheat>) {
        store.save(list)
        cheats = list
        onChanged()
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cheats · $title", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = -1 },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add cheat") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                // Built-in cheats need a game the Pokemon tools understand (none for Black/White).
                if (evolutionChanges != null) item {
                    SectionTitle("Built-in")
                    androidx.compose.material3.OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Evolve without trading", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(checked = evolve, onCheckedChange = { on -> store.evolveWithoutTrading = on; evolve = on; onChanged() })
                            }
                            Text(
                                "Pokémon that evolve by trading evolve at level 36 instead, or by using the item they'd hold " +
                                    "(Metal Coat, King's Rock…). Evolutions this game makes impossible (Feebas in FireRed/LeafGreen, " +
                                    "Magnezone or Leafeon in HeartGold/SoulSilver) work too. Only the running game changes; " +
                                    "the game file stays as it is.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (inGame) Text("Takes effect the next time you start the game.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            if (evolve) TextButton(onClick = { showChanges = true }) { Text("Show what changes") }
                        }
                    }
                }
                item {
                    SectionTitle("Your cheats")
                }
                if (cheats.isEmpty()) item {
                    Text(
                        (if (platform == Platform.NDS) "Add Action Replay codes for this game." else "Add GameShark, Action Replay or CodeBreaker codes for this game.") +
                            " Codes that are switched on run while you play. Some games need their \"master code\" added as a cheat too.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                run {
                    itemsIndexed(cheats) { i, cheat ->
                        ListItem(
                            headlineContent = { Text(cheat.name.ifBlank { "Cheat ${i + 1}" }) },
                            supportingContent = {
                                Text(
                                    cheat.lines.take(3).joinToString("\n") + if (cheat.lines.size > 3) "\n… ${cheat.lines.size - 3} more lines" else "",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = cheat.enabled,
                                    onCheckedChange = { on -> update(cheats.toMutableList().also { it[i] = cheat.copy(enabled = on) }) },
                                )
                            },
                            modifier = Modifier.clickable { editing = i },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showChanges && evolutionChanges != null) {
        val changes by androidx.compose.runtime.produceState<List<String>?>(null) { value = runCatching { evolutionChanges() }.getOrDefault(emptyList()) }
        AlertDialog(
            onDismissRequest = { showChanges = false },
            title = { Text("Evolutions in $title") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(
                        when {
                            changes == null -> "Reading the game's evolutions…"
                            changes!!.isEmpty() -> "This game has no evolutions that need a trade, so nothing changes."
                            else -> changes!!.joinToString("\n")
                        },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showChanges = false }) { Text("OK") } },
        )
    }

    editing?.let { index ->
        CheatEditor(
            initial = cheats.getOrNull(index),
            platform = platform,
            onSave = { cheat ->
                update(if (index < 0) cheats + cheat else cheats.toMutableList().also { it[index] = cheat })
                editing = null
            },
            onDelete = if (index >= 0) ({ editing = null; deleting = index }) else null,
            onDismiss = { editing = null },
        )
    }
    deleting?.let { index ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${cheats.getOrNull(index)?.name?.ifBlank { null } ?: "this cheat"}?") },
            confirmButton = {
                TextButton(onClick = { update(cheats.filterIndexed { i, _ -> i != index }); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheatEditor(
    initial: Cheat?,
    platform: Platform,
    onSave: (Cheat) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var code by remember { mutableStateOf(initial?.lines?.joinToString("\n").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add cheat" else "Edit cheat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it; error = null },
                    label = { Text("Code") },
                    placeholder = { Text(if (platform == Platform.NDS) "XXXXXXXX YYYYYYYY" else "XXXXXXXX YYYY") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onSave(Cheat(name.trim(), CheatCodes.parse(code, platform), initial?.enabled ?: true))
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) = Text(
    text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
)
