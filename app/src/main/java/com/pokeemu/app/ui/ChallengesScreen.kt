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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokeemu.saves.ChallengeStore
import java.text.DateFormat
import java.util.Date

private val ODDS = listOf(0 to "Game default", 512 to "1 in 512", 100 to "1 in 100", 20 to "1 in 20", 1 to "Always")

/**
 * A save slot's challenge modes: shiny odds and a light Nuzlocke helper.
 * They act on the running game's memory only; the ROM and save file aren't edited.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChallengesScreen(title: String, store: ChallengeStore, onBack: () -> Unit, onChanged: () -> Unit = {}) {
    var odds by remember { mutableStateOf(store.shinyOdds) }
    var nuzlocke by remember { mutableStateOf(store.nuzlocke) }
    var log by remember { mutableStateOf(store.log().asReversed()) }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Challenges · $title", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "For save slot \"${store.slot}\". These change only the running game, never the game file or your save file.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shiny odds", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "How often wild Pokémon are shiny. \"Always\" makes it a shiny-only run. Nature, gender and ability stay the same. " +
                                "The battle intro may not sparkle, but the Pokémon you catch is shiny.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((value, label) in ODDS) FilterChip(
                                selected = odds == value,
                                onClick = { odds = value; store.shinyOdds = value; onChanged() },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Nuzlocke", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = nuzlocke, onCheckedChange = { nuzlocke = it; store.nuzlocke = it; onChanged() })
                        }
                        Text(
                            "Keeps a log of your encounters and team. A Pokémon that faints is marked dead, and you're warned " +
                                "if it's still in your party when a battle starts. The rest is up to you: one catch per area, nickname everything.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (log.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Log", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { store.clearLog(); log = emptyList(); onChanged() }) { Text("Clear") }
                    }
                }
                items(log) { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (e.kind) { ChallengeStore.Kind.DEATH -> "💀"; ChallengeStore.Kind.JOINED -> "⭐"; ChallengeStore.Kind.ENCOUNTER -> "🌿" },
                            Modifier.padding(end = 10.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.text, style = MaterialTheme.typography.bodyMedium)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
