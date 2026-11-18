package com.pokeemu.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pokeemu.core.label

/**
 * Searchable list of (id, name). When [names] is empty (table not found in
 * the ROM) it falls back to typing a number.
 */
@Composable
fun IdPicker(
    title: String,
    names: List<String>,
    allowZero: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** Optional picture before each entry (sprite, item icon). */
    leading: (@Composable (Int) -> Unit)? = null,
    /** Which ids may be offered (e.g. species the game really has); all by default. */
    allowed: (Int) -> Boolean = { true },
) {
    var query by remember { mutableStateOf("") }
    val entries = remember(names, allowZero) {
        val none = if (allowZero) listOf(0 to "(none)") else emptyList()
        // Skip index 0 and placeholder entries like "??????????" / "-------".
        none + names.mapIndexedNotNull { id, name -> if (id == 0 || name.isBlank() || name.all { it == '?' || it == '-' } || !allowed(id)) null else id to name }
    }
    val filtered = remember(query, entries) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { (id, name) -> name.contains(q, ignoreCase = true) || id.toString() == q }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it },
                    label = { Text(if (names.isEmpty()) "Number" else "Search or number") },
                    singleLine = true,
                    keyboardOptions = if (names.isEmpty()) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (names.isEmpty()) {
                    Text("This ROM's name table wasn't found, so enter the number.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.first }) { (id, name) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (leading != null) {
                                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { if (id != 0) leading(id) }
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(name, Modifier.weight(1f))
                                Text("#$id", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (names.isEmpty()) TextButton(onClick = { query.trim().toIntOrNull()?.let(onPick) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Picks a move for [species]: only moves it can learn in this game (from the
 * ROM's own learnsets, including what earlier evolutions learn), each with how
 * it's learned. Moves it already knows stay available. "Show all moves" covers
 * whatever the ROM's tables don't (e.g. move tutors).
 */
@Composable
fun MovePicker(
    title: String,
    data: com.pokeemu.core.GameData,
    species: Int,
    known: List<Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val learnsets = data.learnsets
    val learnable = remember(learnsets, species) { learnsets?.learnable(species).orEmpty() }
    if (learnable.isEmpty()) {
        // No learnset data for this ROM or species: every move, as before.
        IdPicker(title, data.moveNames, allowZero = true, onPick = onPick, onDismiss = onDismiss)
        return
    }
    var showAll by remember { mutableStateOf(false) }
    if (showAll) {
        IdPicker(title, data.moveNames, allowZero = true, onPick = onPick, onDismiss = { showAll = false })
        return
    }
    var query by remember { mutableStateOf("") }
    val entries = remember(learnable, known) {
        val list = mutableListOf(Triple(0, "(none)", ""))
        for ((move, source) in learnable) list += Triple(move, data.moveName(move), source.label(data::speciesName))
        for (move in known) if (move != 0 && move !in learnable) list += Triple(move, data.moveName(move), "already known")
        list
    }
    val filtered = remember(query, entries) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { (id, name, how) -> name.contains(q, ignoreCase = true) || how.contains(q, ignoreCase = true) || id.toString() == q }
    }
    val missing = remember(learnsets) {
        val has = learnsets?.has.orEmpty()
        buildList {
            if (com.pokeemu.core.Learnsets.Part.TM !in has) add("TM/HM")
            if (com.pokeemu.core.Learnsets.Part.EGG !in has) add("egg")
            if (com.pokeemu.core.Learnsets.Part.TUTOR !in has) add("tutor")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Moves ${data.speciesName(species)} can learn in this game." +
                        if (missing.isNotEmpty()) " This ROM's ${missing.joinToString(" and ")} moves couldn't be read; use Show all moves for those." else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.first }) { (id, name, how) ->
                        val info = data.moveInfo(id)
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                if (how.isNotEmpty()) Text(how, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (info != null) {
                                Column(horizontalAlignment = Alignment.End) {
                                    TypeChip(data.typeName(info.type), compact = true)
                                    Text(
                                        if (info.power > 1) "${info.power}" else "—",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAll = true }) { Text("Show all moves") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
