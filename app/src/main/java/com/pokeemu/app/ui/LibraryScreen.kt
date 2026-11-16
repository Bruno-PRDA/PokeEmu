package com.pokeemu.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pokeemu.app.RomEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    roms: List<RomEntry>,
    hasFolder: Boolean,
    loading: Boolean,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (RomEntry) -> Unit,
    onManageSaves: (RomEntry) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Games") },
                actions = {
                    if (hasFolder) {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Rescan") }
                    }
                    IconButton(onClick = onPickFolder) { Icon(Icons.Default.Folder, "Choose ROM folder") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !hasFolder || roms.isEmpty() -> EmptyState(hasFolder, onPickFolder, Modifier.align(Alignment.Center))
                else -> LazyColumn {
                    items(roms, key = { it.uri.toString() }) { rom ->
                        ListItem(
                            headlineContent = { Text(rom.fileName.substringBeforeLast('.')) },
                            supportingContent = { Text("${rom.header.title} · ${rom.header.gameCode}") },
                            trailingContent = {
                                IconButton(onClick = { onManageSaves(rom) }) { Icon(Icons.Default.Save, "Manage saves") }
                            },
                            modifier = Modifier.clickable { onOpen(rom) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasFolder: Boolean, onPickFolder: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (hasFolder) "No .gba files found in that folder." else "Pick the folder where you keep your GBA ROMs.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onPickFolder) { Text(if (hasFolder) "Choose another folder" else "Choose ROM folder") }
    }
}
