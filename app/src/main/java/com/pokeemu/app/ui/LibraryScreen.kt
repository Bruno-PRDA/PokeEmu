package com.pokeemu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EmojiEvents
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
    onChallenges: (RomEntry) -> Unit,
    onRemove: (RomEntry) -> Unit,
    onRename: (RomEntry, String) -> Unit = { _, _ -> },
) {
    var renaming by remember { mutableStateOf<RomEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RomEntry?>(null) }
    var removing by remember { mutableStateOf<RomEntry?>(null) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Logo() },
                colors = androidx.compose.material3.TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = PokeColors.Background),
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
                if (roms.isNotEmpty()) item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    Text("YOUR GAMES · ${roms.size}", style = SectionHeadingStyle)
                }
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

    renaming?.let { rom ->
        var text by remember(rom) { mutableStateOf(rom.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.take(60) }, singleLine = true,
                    label = { Text("Name in the main menu") },
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { renaming = null; onRename(rom, text) }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
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
                onChallenges = onChallenges,
                onRemove = { removing = it },
                onRename = { renaming = it },
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
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(44.dp))
        PixelText("POKEEMU", pixel = 3.dp, top = Color(0xFFFFFFFF), bottom = PokeColors.Red, shadow = Color(0xFF3A0B0D))
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
        Modifier.fillMaxWidth().aspectRatio(1f).clip(CardShape)
            .border(1.dp, PokeColors.OutlineSoft, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardShape,
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = PokeColors.Surface1),
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
                PixelText(rom.name, Modifier.align(Alignment.BottomStart).padding(10.dp), pixel = 1.5.dp)
            }
            return@ElevatedCard
        }
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GameIcon(rom, icon, Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            PixelText(rom.name, pixel = 1.5.dp)
        }
    }
}

@Composable
private fun PlatformBadge(rom: RomEntry, modifier: Modifier) {
    Text(
        if (rom.header.platform == Platform.NDS) "DS" else "GBA",
        modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .border(1.dp, (if (rom.header.platform == Platform.NDS) PokeColors.Teal else PokeColors.Gold).copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (rom.header.platform == Platform.NDS) PokeColors.Teal else PokeColors.Gold,
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AddTile(onAdd: () -> Unit) {
    androidx.compose.material3.Card(
        onClick = onAdd, modifier = Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, PokeColors.Outline, CardShape), shape = CardShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = PokeColors.SurfaceLow),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(PokeColors.Red), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, "Add game", Modifier.size(32.dp), tint = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            PixelText("ADD GAME", pixel = 1.5.dp, top = PokeColors.Text, bottom = PokeColors.TextDim)
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
            modifier.clip(RoundedCornerShape(16.dp)).background(if (ds) PokeColors.Teal.copy(alpha = 0.25f) else PokeColors.RedDeep),
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
    onChallenges: (RomEntry) -> Unit,
    onRemove: (RomEntry) -> Unit,
    onRename: (RomEntry) -> Unit,
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
        androidx.compose.material3.Button(
            onClick = { onAction(onOpen) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).height(52.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Play", style = MaterialTheme.typography.titleMedium)
        }
        val actions = buildList {
            add(Triple(Icons.Default.Save, "Saves", onManageSaves))
            // Black, White and other DS games the Pokemon tools don't understand only play.
            if (rom.header.pokemonTools) add(Triple(Icons.Default.Edit, "Pokémon", onEditPokemon))
            add(Triple(Icons.Default.Code, "Cheats", onCheats))
            if (rom.header.liveTools) add(Triple(Icons.Default.EmojiEvents, "Challenges", onChallenges))
            add(Triple(Icons.Default.DriveFileRenameOutline, "Rename", onRename))
            add(Triple(Icons.Default.Image, "Cover", onChangeCover))
            if (hasCustomCover) add(Triple(Icons.Default.HideImage, "No cover", onRemoveCover))
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (icon, label, action) -> ActionTile(icon, label, Modifier.weight(1f)) { onAction(action) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        TextButton(onClick = { onAction(onRemove) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("Remove game", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A square button with an icon over its label, for the game options sheet. */
@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(PokeColors.Surface2).border(1.dp, PokeColors.OutlineSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = PokeColors.Gold)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
