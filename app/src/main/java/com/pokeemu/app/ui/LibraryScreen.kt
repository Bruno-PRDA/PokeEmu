package com.pokeemu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pokeemu.app.R
import com.pokeemu.app.RomEntry
import com.pokeemu.saves.Platform

/**
 * The main menu: a grid of games (tap to play, long-press for the game's
 * options) with a "+" tile to add one. The menu holds the ROM folder options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    roms: List<RomEntry>,
    /** Non-null while games are being imported: what is happening. */
    busy: String?,
    icon: suspend (RomEntry) -> Bitmap?,
    /** The game's picture (chosen cover or last-played screen), or null. */
    cover: suspend (RomEntry) -> Bitmap?,
    /** Bumped when covers may have changed (back from a game, cover picked...). */
    coverVersion: Int,
    hasCustomCover: (RomEntry) -> Boolean,
    onChangeCover: (RomEntry) -> Unit,
    onRemoveCover: (RomEntry) -> Unit,
    onAdd: () -> Unit,
    onImportFolder: () -> Unit,
    onGlobalPc: () -> Unit,
    onOpen: (RomEntry) -> Unit,
    onManageSaves: (RomEntry) -> Unit,
    onEditPokemon: (RomEntry) -> Unit,
    onCheats: (RomEntry) -> Unit,
    onRemove: (RomEntry) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RomEntry?>(null) }
    var removing by remember { mutableStateOf<RomEntry?>(null) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Logo() },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Global Pokémon PC") },
                                leadingIcon = { Icon(Icons.Default.Inventory2, null) },
                                onClick = { menuOpen = false; onGlobalPc() },
                            )
                            DropdownMenuItem(
                                text = { Text("Import a folder of games") },
                                leadingIcon = { Icon(Icons.Default.Folder, null) },
                                onClick = { menuOpen = false; onImportFolder() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(roms, key = { it.uri.toString() }) { rom ->
                    GameTile(rom, icon, cover, coverVersion, onClick = { onOpen(rom) }, onLongClick = { selected = rom })
                }
                item(key = "add") { AddTile(onAdd) }
                if (roms.isEmpty() && busy == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Tap + to add a GBA or DS game. It is copied into the app, so you can delete the " +
                                "downloaded file afterwards. Long-press a game for its saves and Pokémon.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (busy != null) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(busy, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    removing?.let { rom ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${rom.name}?") },
            text = { Text("The game is deleted from the app. Its saves are kept and come back if you add it again.") },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(rom) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    selected?.let { rom ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            GameOptions(
                rom, icon,
                onAction = { action ->
                    selected = null
                    action(rom)
                },
                onOpen = onOpen,
                onManageSaves = onManageSaves,
                onEditPokemon = onEditPokemon,
                onCheats = onCheats,
                onRemove = { removing = it },
                hasCustomCover = hasCustomCover(rom),
                onChangeCover = onChangeCover,
                onRemoveCover = onRemoveCover,
            )
        }
    }
}

@Composable
private fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(40.dp))
        Text("PokeEmu", fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameTile(
    rom: RomEntry,
    icon: suspend (RomEntry) -> Bitmap?,
    cover: suspend (RomEntry) -> Bitmap?,
    coverVersion: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var picture by remember(rom.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(rom.uri, coverVersion) { picture = cover(rom) }
    ElevatedCard(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(CardShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardShape,
    ) {
        val image = picture
        if (image != null) {
            // The game's picture fills the tile; the name sits on a soft shadow at the bottom.
            Box(Modifier.fillMaxSize()) {
                Image(
                    image.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    // Game screenshots are pixel art: keep them sharp. Photos (large) are smoothed.
                    filterQuality = if (image.width <= 512) FilterQuality.None else FilterQuality.Medium,
                )
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
                )
                PlatformBadge(rom, Modifier.align(Alignment.TopStart).padding(8.dp))
                Text(
                    rom.name,
                    Modifier.align(Alignment.BottomStart).padding(12.dp),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            return@ElevatedCard
        }
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GameIcon(rom, icon, Modifier.size(72.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                rom.name,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlatformBadge(rom: RomEntry, modifier: Modifier) {
    Text(
        if (rom.header.platform == Platform.NDS) "DS" else "GBA",
        modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AddTile(onAdd: () -> Unit) {
    OutlinedCard(onClick = onAdd, modifier = Modifier.fillMaxWidth().aspectRatio(1f), shape = CardShape) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, "Add game", Modifier.size(56.dp))
        }
    }
}

/** The DS banner icon, or a badge for GBA games (which have no icon). */
@Composable
private fun GameIcon(rom: RomEntry, icon: suspend (RomEntry) -> Bitmap?, modifier: Modifier, small: Boolean = false) {
    var bitmap by remember(rom.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(rom.uri) { bitmap = icon(rom) }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), null, modifier, filterQuality = FilterQuality.None)
    } else {
        val ds = rom.header.platform == Platform.NDS
        Box(
            modifier.clip(RoundedCornerShape(16.dp)).background(if (ds) Color(0xFF455A64) else Color(0xFF5E35B1)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.CatchingPokemon, null, Modifier.size(if (small) 28.dp else 36.dp), tint = Color.White.copy(alpha = 0.9f))
            if (!small) Text(
                if (ds) "DS" else "GBA",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun GameOptions(
    rom: RomEntry,
    icon: suspend (RomEntry) -> Bitmap?,
    onAction: ((RomEntry) -> Unit) -> Unit,
    onOpen: (RomEntry) -> Unit,
    onManageSaves: (RomEntry) -> Unit,
    onEditPokemon: (RomEntry) -> Unit,
    onCheats: (RomEntry) -> Unit,
    onRemove: (RomEntry) -> Unit,
    hasCustomCover: Boolean,
    onChangeCover: (RomEntry) -> Unit,
    onRemoveCover: (RomEntry) -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GameIcon(rom, icon, Modifier.size(48.dp), small = true)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(rom.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${rom.header.platform.label} · ${rom.header.title} · ${rom.header.gameCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Option(Icons.Default.PlayArrow, "Play") { onAction(onOpen) }
        Option(Icons.Default.Save, "Save manager") { onAction(onManageSaves) }
        Option(Icons.Default.Edit, "Edit Pokémon") { onAction(onEditPokemon) }
        Option(Icons.Default.Code, "Cheats") { onAction(onCheats) }
        Option(Icons.Default.Image, "Change cover picture") { onAction(onChangeCover) }
        if (hasCustomCover) Option(Icons.Default.HideImage, "Remove cover picture") { onAction(onRemoveCover) }
        Option(Icons.Default.Delete, "Remove game") { onAction(onRemove) }
    }
}

@Composable
private fun Option(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val CardShape = RoundedCornerShape(20.dp)
