package com.pokeemu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pokeemu.app.CheatSearch
import com.pokeemu.saves.PinStore

/** What the cheat search screen can do with the running game (the player provides it). */
interface CheatSearchHost {
    /** The current search, or null before the first one. */
    val search: CheatSearch?
    fun newSearch(size: Int): Boolean
    fun filter(compare: CheatSearch.Compare, value: Long?): Boolean
    fun results(): List<CheatSearch.Result>
    /** Writes [value] once. */
    fun setOnce(address: Long, size: Int, value: Long): Boolean
    val pins: List<PinStore.Pin>
    fun putPin(pin: PinStore.Pin)
    fun removePin(address: Long)
}

/**
 * Finds a value in the game's memory and changes or freezes it. Start a
 * search, change the value in the game (spend money, lose HP), come back and
 * narrow it down until a few addresses are left.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CheatSearchScreen(host: CheatSearchHost, onBack: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var size by remember { mutableIntStateOf(host.search?.size ?: 2) }
    var valueText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    /** A result being set or frozen. */
    var editing by remember { mutableStateOf<Pair<CheatSearch.Result, Boolean>?>(null) }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cheat search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the game") } },
            )
        },
    ) { padding ->
        tick.let { }
        val search = host.search
        val results = remember(tick, search) { host.results() }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (search == null || !search.started) {
                            Text("Find a value in the game's memory", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "1. Start a search. 2. Go back to the game and change the value (spend money, use an item, lose HP). " +
                                    "3. Come back and say how it changed, or type its new value. Repeat until a few addresses are left.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("${"%,d".format(search.count)} possible addresses", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (search.steps == 0) "Change the value in the game, then come back and pick how it changed."
                                else "Change it again and narrow down, until a few are left.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Value size: 1 byte for values up to 255, 2 bytes up to 65,535, 4 bytes for bigger ones", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((s, label) in listOf(1 to "1 byte", 2 to "2 bytes", 4 to "4 bytes")) {
                                FilterChip(selected = size == s, onClick = { size = s }, label = { Text(label) })
                            }
                        }
                        Button(onClick = { error = if (host.newSearch(size)) null else "Couldn't read the game's memory"; tick++ }, Modifier.fillMaxWidth()) {
                            Text(if (search?.started == true) "New search" else "Start search")
                        }
                    }
                }
            }
            if (search?.started == true) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Since the last look, the value…", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in listOf(CheatSearch.Compare.CHANGED, CheatSearch.Compare.UNCHANGED, CheatSearch.Compare.INCREASED, CheatSearch.Compare.DECREASED)) {
                                FilledTonalButton(onClick = { error = if (host.filter(c, null)) null else "Couldn't read the game's memory"; tick++ }) { Text(c.label) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                valueText, { valueText = it.filter(Char::isDigit).take(10) },
                                label = { Text("…is now exactly") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            FilledTonalButton(onClick = {
                                val v = valueText.toLongOrNull()
                                error = when {
                                    v == null -> "Type the value it has now"
                                    v > search.maxValue -> "Too big for ${search.size} byte${if (search.size > 1) "s" else ""}: pick a bigger size and start again"
                                    !host.filter(CheatSearch.Compare.EQUAL, v) -> "Couldn't read the game's memory"
                                    else -> null
                                }
                                tick++
                            }) { Text("Find") }
                        }
                        Text(
                            "Some games scramble values like money: if \"exactly\" finds nothing, use the buttons above instead.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            if (search?.started == true && results.isEmpty() && search.count > 0) item {
                Text("Addresses are listed once 200 or fewer are left.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (results.isNotEmpty()) {
                item { Text("Found", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(results, key = { it.address }) { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("%08X".format(r.address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text("now ${r.value}" + if (r.previous != r.value) " (was ${r.previous})" else "", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { editing = r to false }) { Text("Set") }
                        OutlinedButton(onClick = { editing = r to true }) { Text("Freeze") }
                    }
                }
            }
            val pins = host.pins
            if (pins.isNotEmpty()) {
                item { Text("Frozen values", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(pins, key = { "pin" + it.address }) { pin ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pin.label.ifBlank { "%08X".format(pin.address) }, style = MaterialTheme.typography.bodyMedium)
                            Text("%08X = %d".format(pin.address, pin.value), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = pin.enabled, onCheckedChange = { on -> host.putPin(pin.copy(enabled = on)); tick++ })
                        IconButton(onClick = { host.removePin(pin.address); tick++ }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
                item {
                    Text(
                        "Frozen values are written while the game runs. If one stops working after moving to another area, " +
                            "the game moved it: search for it again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    editing?.let { (r, freeze) ->
        val s = host.search?.size ?: size
        var v by remember(r) { mutableStateOf(r.value.toString()) }
        var label by remember(r) { mutableStateOf("") }
        val value = v.toLongOrNull()?.takeIf { it <= (1L shl (8 * s)) - 1 }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (freeze) "Freeze %08X".format(r.address) else "Set %08X".format(r.address)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(v, { v = it.filter(Char::isDigit).take(10) }, label = { Text("Value") }, singleLine = true, isError = value == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (freeze) OutlinedTextField(label, { label = it.take(30) }, label = { Text("Name (e.g. Money)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = value != null, onClick = {
                    editing = null
                    if (freeze) host.putPin(PinStore.Pin(r.address, s, value!!, label.trim()))
                    else host.setOnce(r.address, s, value!!)
                    tick++
                }) { Text(if (freeze) "Freeze" else "Set") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
