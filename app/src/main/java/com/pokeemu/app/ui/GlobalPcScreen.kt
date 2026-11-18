package com.pokeemu.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.app.OpenSave
import com.pokeemu.app.RomEntry
import com.pokeemu.core.GlobalPc
import com.pokeemu.core.Mon
import com.pokeemu.core.Slot
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import com.pokeemu.gen4.Gen3To4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The global Pokémon PC: storage shared by every game. The top half is the
 * global PC, the bottom half one game's party and boxes; tapping a Pokémon
 * moves it to the other side. Each move is written to the game's save at once
 * (with a backup of the save as it was).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalPcScreen(games: List<RomEntry>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pc = remember { com.pokeemu.gen5.Gen4To5.register(); GlobalPc(File(context.filesDir, "global-pc")) }
    val origins = remember { Origins(context) }
    var stored by remember { mutableStateOf(pc.list()) }
    var game by remember { mutableStateOf<RomEntry?>(null) }
    var open by remember { mutableStateOf<OpenSave?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var box by remember { mutableIntStateOf(0) } // -1 = party
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var deposit by remember { mutableStateOf<Slot?>(null) }
    var withdraw by remember { mutableStateOf<StoredMon?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun openGame(rom: RomEntry) {
        game = rom; open = null; status = "Opening save…"; box = 0
        scope.launch {
            try {
                open = OpenSave.open(context, rom) { status = it }
                status = null
            } catch (e: Exception) {
                status = "Can't open this game's save: ${e.message}"
            }
        }
    }

    /** Runs a transfer; on failure the save is re-read so the screen matches the file. */
    fun transfer(block: suspend (OpenSave) -> String?) {
        val o = open ?: return
        val rom = game ?: return
        busy = true
        scope.launch {
            try {
                block(o)?.let(::toast)
            } catch (e: IllegalArgumentException) {
                toast(e.message ?: "Not allowed")
                runCatching { open = OpenSave.open(context, rom) }
            } catch (e: Exception) {
                toast("Not moved: ${e.message}")
                runCatching { open = OpenSave.open(context, rom) }
            }
            stored = withContext(Dispatchers.IO) { pc.list() }
            busy = false
            tick++
        }
    }

    fun doDeposit(slot: Slot) = transfer { o ->
        val e = o.editor
        val mon = e[slot] ?: return@transfer null
        val name = displayName(e.data.speciesName(mon.species), mon.nickname)
        pc.requireCanDeposit(mon)
        e.delete(slot) // refuses to empty the party, before anything is written
        val entry = withContext(Dispatchers.IO) {
            pc.deposit(e, mon, game!!.name, o.romId).also { stored ->
                // Keep its picture with it: the PC shows it even after its game is removed.
                runCatching { o.sprites?.pokemon(mon.species, mon.isShiny)?.let { savePng(it, pc.spriteFile(stored)) } }
            }
        }
        try {
            o.write()
        } catch (ex: Exception) {
            withContext(Dispatchers.IO) { pc.remove(entry) } // the game still has it
            throw ex
        }
        "$name moved to the global PC"
    }

    fun doWithdraw(s: StoredMon) = transfer { o ->
        val e = o.editor
        val result = Transfer.adapt(s, e, o.romId)
        val target = firstFreeSlot(o, preferBox = box) ?: throw IllegalArgumentException("This game's PC is full")
        e.insert(target, result.mon)
        o.write()
        withContext(Dispatchers.IO) { pc.remove(s) } // only once the game has it
        (listOf("${displayName(s.speciesName, s.nickname)} moved into ${e.boxName((target as Slot.Box).box)}") + result.notes)
            .joinToString("\n")
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pokémon PC") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- global PC ----
            Text(
                "Global PC · ${stored.size} Pokémon",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (stored.isEmpty()) {
                    Text(
                        "Empty. Pick a game below and tap one of its Pokémon to move it here. " +
                            "From here it can go into any of your games.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(stored, key = { it.id }) { s ->
                            StoredCard(s, pc.spriteFile(s)) {
                                if (open == null) toast(if (game == null) "Pick a game below to move it into" else "That game's save isn't open")
                                else withdraw = s
                            }
                        }
                    }
                }
            }
            HorizontalDivider()

            // ---- one game ----
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(games, key = { it.uri.toString() }) { rom ->
                    FilterChip(selected = game == rom, onClick = { if (game != rom) openGame(rom) }, label = { Text(rom.name) })
                }
            }
            Box(Modifier.weight(1.25f).fillMaxWidth()) {
                val o = open
                when {
                    game == null -> CenteredNote("Pick a game to move Pokémon in or out of it.")
                    o == null -> CenteredNote(status ?: "", loading = status?.startsWith("Can't") != true)
                    else -> GameBoxes(
                        o, box, tick, onBox = { box = it }, onSlot = { deposit = it },
                        isForeign = { mon -> origins.isForeign(mon, game!!.name, o.editor.fileExtension) },
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    val o = open
    deposit?.let { slot ->
        val mon = o?.editor?.get(slot)
        if (mon == null) { deposit = null; return@let }
        val name = displayName(o.editor.data.speciesName(mon.species), mon.nickname)
        AlertDialog(
            onDismissRequest = { deposit = null },
            title = { Text("Move $name to the global PC?") },
            text = {
                Text(
                    "It leaves ${game?.name}. You can move it into this or any other game later." +
                        (origins.describe(mon)?.let { "\n\nOrigin: $it" } ?: ""),
                )
            },
            confirmButton = { TextButton(onClick = { deposit = null; doDeposit(slot) }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { deposit = null }) { Text("Cancel") } },
        )
    }
    withdraw?.let { s ->
        val sameGame = o?.romId == s.fromRomId
        val toDs = s.format == "pk3" && o?.editor?.fileExtension == "pk4"
        val toGen5 = s.format != "pk5" && o?.editor?.fileExtension == "pk5"
        AlertDialog(
            onDismissRequest = { withdraw = null },
            title = { Text("Move ${displayName(s.speciesName, s.nickname)} into ${game?.name}?") },
            text = {
                Text(
                    "It goes into the first free spot in this game's PC." +
                        (if (sameGame) "" else " Its species, moves and held item are matched by name in this game; " +
                            "anything the game doesn't have is left out.") +
                        (if (toDs) " Like Pal Park, it's met at Pal Park and can't go back to a GBA game afterwards." else "") +
                        (if (toGen5) " Like Poké Transfer, it's met through Poké Transfer and can't go back to an older game afterwards." else ""),
                )
            },
            confirmButton = { TextButton(onClick = { withdraw = null; doWithdraw(s) }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { withdraw = null }) { Text("Cancel") } },
        )
    }
}

/** First empty box slot, starting with [preferBox] (if it's a box). */
private fun firstFreeSlot(o: OpenSave, preferBox: Int): Slot? {
    val e = o.editor
    val order = (if (preferBox >= 0) listOf(preferBox) else emptyList()) + (0 until e.boxCount).filter { it != preferBox }
    for (b in order) {
        val i = e.box(b).indexOfFirst { it == null }
        if (i >= 0) return Slot.Box(b, i)
    }
    return null
}

private fun displayName(species: String, nickname: String) =
    if (nickname.isBlank() || nickname.equals(species, ignoreCase = true)) species else "$nickname ($species)"

@Composable
private fun CenteredNote(text: String, loading: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator()
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun StoredCard(s: StoredMon, sprite: File, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SpriteTile(56.dp) { SavedSprite(sprite, 52.dp) }
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                s.nickname.ifBlank { s.speciesName } + if (s.shiny) " ★" else "",
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val nicknamed = s.nickname.isNotBlank() && Transfer.normalize(s.nickname) != Transfer.normalize(s.speciesName)
            Text(
                listOfNotNull(s.speciesName.takeIf { nicknamed }, s.level?.let { "Lv $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            s.originGame?.takeIf { Transfer.normalize(it) != Transfer.normalize(s.fromGame) }?.let {
                Text("caught in $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "from ${s.fromGame} · ${com.pokeemu.core.Transfer.generationLabel(s.format)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}

/** A stored Pokémon's saved sprite. */
@Composable
private fun SavedSprite(file: File, size: androidx.compose.ui.unit.Dp) {
    val image by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    // Same pixel scale as live sprites (64 px per [size]); DS sprites' wider margin is clipped.
    androidx.compose.foundation.layout.Box(Modifier.size(size).clipToBounds(), contentAlignment = Alignment.Center) {
        image?.let { androidx.compose.foundation.Image(it, null, Modifier.requiredSize(size * (it.width / 64f)), filterQuality = androidx.compose.ui.graphics.FilterQuality.None) }
    }
}

private fun savePng(p: com.pokeemu.core.Picture, file: File) {
    val bitmap = android.graphics.Bitmap.createBitmap(p.argb, p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}

@Composable
private fun GameBoxes(o: OpenSave, box: Int, tick: Int, onBox: (Int) -> Unit, onSlot: (Slot) -> Unit, isForeign: (Mon) -> Boolean) {
    val e = o.editor
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onBox(if (box <= -1) e.boxCount - 1 else box - 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous box")
            }
            Text(
                if (box < 0) "Party" else e.boxName(box),
                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = { onBox(if (box >= e.boxCount - 1) -1 else box + 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next box")
            }
        }
        val slots: List<Pair<Slot, Mon?>> = if (box < 0) {
            val party = e.partyMembers
            (0 until 6).map { Slot.Party(it) to party.getOrNull(it) }
        } else {
            e.box(box).mapIndexed { i, m -> Slot.Box(box, i) to m }
        }
        key(tick) {
            LazyVerticalGrid(
                GridCells.Fixed(6),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(slots) { (slot, mon) ->
                    SlotCell(o.sprites, mon, mon?.let(e::level), foreign = mon?.let(isForeign) == true) { onSlot(slot) }
                }
            }
        }
    }
}

@Composable
private fun key(tick: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(tick) { content() }

@Composable
private fun SlotCell(sprites: com.pokeemu.core.SpriteSource?, mon: Mon?, level: Int?, foreign: Boolean, onClick: () -> Unit) {
    if (mon == null) {
        OutlinedCard(Modifier.fillMaxWidth().aspectRatio(0.9f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
        return
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(0.9f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxSize()) {
        if (foreign) ForeignDot(Modifier.align(Alignment.TopEnd).padding(4.dp))
        Text(
            (if (mon.isShiny) "★" else "") + if (mon.isEgg) "Egg" else (level?.toString() ?: ""),
            Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp),
            fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier.fillMaxSize().padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MonSprite(sprites, if (mon.isEgg) 0 else mon.species, mon.isShiny, 44.dp)
        }
        }
    }
}
