package com.pokeemu.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.app.OpenSave
import com.pokeemu.app.RomEntry
import com.pokeemu.core.GameData
import com.pokeemu.core.GlobalPc
import com.pokeemu.core.Mon
import com.pokeemu.core.Slot
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the player is holding, like the "Move" hand of the in-game PC. */
private sealed interface Held {
    data class Stored(val mon: StoredMon) : Held
    data class InGame(val slot: Slot) : Held
}

// A clean dark palette.
private val Bg = PokeColors.Background
private val Panel = PokeColors.SurfaceLow
private val PanelHeader = PokeColors.Surface2
private val Spot = PokeColors.Surface2
private val SpotLine = PokeColors.OutlineSoft
private val Accent = PokeColors.Gold
private val TextMain = PokeColors.Text
private val TextDim = PokeColors.TextDim
private val Good = Color(0xFF7BD88F)
private val Bad = Color(0xFFFF8A80)

/**
 * The global Pokémon PC, laid out like the games' own PC: the global boxes on
 * top; below, a game picker, then that game's boxes and party. Tap a Pokémon to
 * pick it up and tap where it goes; hold one for everything about it. Each move
 * is written to the game's save at once (with a backup of the save as it was).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalPcScreen(games: List<RomEntry>, cover: suspend (RomEntry) -> Bitmap?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pc = remember { com.pokeemu.gen5.Gen4To5.register(); GlobalPc(File(context.filesDir, "global-pc")) }
    val origins = remember { Origins(context) }
    var stored by remember { mutableStateOf(pc.list()) }
    var game by remember { mutableStateOf<RomEntry?>(null) }
    var open by remember { mutableStateOf<OpenSave?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var box by remember { mutableIntStateOf(0) } // -1 = party
    var globalBox by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Held?>(null) }
    /** The Pokémon whose details are open (held down). */
    var details by remember { mutableStateOf<Held?>(null) }
    /** A withdrawal that changes the Pokémon's generation, waiting for a yes. */
    var confirm by remember { mutableStateOf<Pair<StoredMon, Slot>?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val places by produceState(emptyMap<Int, String>(), game) { value = game?.let { com.pokeemu.app.MetInfo.places(context, it) }.orEmpty() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val bytes = exportBytes
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
                .onSuccess { toast("Exported") }.onFailure { toast("Export failed: ${it.message}") }
        }
        exportBytes = null
    }

    fun openGame(rom: RomEntry) {
        game = rom; open = null; status = "Opening save…"; box = 0
        if (held is Held.InGame) held = null
        scope.launch {
            try {
                open = OpenSave.open(context, rom) { status = it }
                status = null
            } catch (e: Exception) {
                status = "Can't open this game's save: ${e.message}"
            }
        }
    }

    fun closeGame() {
        game = null; open = null; status = null
        if (held is Held.InGame) held = null
    }

    /** Runs a change; on failure the save is re-read so the screen matches the file. */
    fun transfer(block: suspend (OpenSave?) -> String?) {
        val o = open
        val rom = game
        busy = true
        scope.launch {
            try {
                block(o)?.let(::toast)
            } catch (e: IllegalArgumentException) {
                toast(e.message ?: "Not allowed")
                if (rom != null) runCatching { open = OpenSave.open(context, rom) }
            } catch (e: Exception) {
                toast("Not done: ${e.message}")
                if (rom != null) runCatching { open = OpenSave.open(context, rom) }
            }
            stored = withContext(Dispatchers.IO) { pc.list() }
            held = null
            busy = false
            tick++
        }
    }

    fun doDeposit(slot: Slot) = transfer { o ->
        val e = o!!.editor
        val mon = e[slot] ?: return@transfer null
        val name = displayName(e.data.speciesName(mon.species), mon.nickname)
        pc.requireCanDeposit(mon)
        e.delete(slot) // refuses to empty the party, before anything is written
        val entry = withContext(Dispatchers.IO) {
            pc.deposit(e, mon, game!!.name, o.romId).also { s ->
                // Keep its picture with it: the PC shows it even after its game is removed.
                runCatching { o.sprites?.pokemon(mon.species, mon.isShiny)?.let { savePng(it, pc.spriteFile(s)) } }
            }
        }
        try {
            o.write()
        } catch (ex: Exception) {
            withContext(Dispatchers.IO) { pc.remove(entry) } // the game still has it
            throw ex
        }
        globalBox = stored.size / SLOTS // show where it landed
        "$name moved to the global PC"
    }

    fun doWithdraw(s: StoredMon, target: Slot) = transfer { o ->
        val e = o!!.editor
        val result = Transfer.adapt(s, e, o.romId)
        e.insert(target, result.mon)
        o.write()
        withContext(Dispatchers.IO) { pc.remove(s) } // only once the game has it
        val where = if (target is Slot.Box) e.boxName(target.box) else "the party"
        (listOf("${displayName(s.speciesName, s.nickname)} moved into $where") + result.notes).joinToString("\n")
    }

    fun doMove(from: Slot, to: Slot) = transfer { o ->
        o!!.editor.move(from, to)
        o.write()
        null
    }

    /** A withdrawal; asks first when it converts the Pokémon to a newer generation (it can't go back). */
    fun withdrawTo(s: StoredMon, target: Slot) {
        val ext = open?.editor?.fileExtension
        val converts = (s.format == "pk3" && ext != "pk3") || (s.format == "pk4" && ext == "pk5")
        if (converts) confirm = s to target else doWithdraw(s, target)
    }

    fun onGlobalTap(s: StoredMon?) {
        when (val h = held) {
            is Held.InGame -> doDeposit(h.slot)
            is Held.Stored -> held = if (s == null || s == h.mon) null else Held.Stored(s)
            null -> if (s != null) held = Held.Stored(s)
        }
    }

    fun onGameTap(slot: Slot, mon: Mon?) {
        when (val h = held) {
            is Held.Stored -> if (mon == null) withdrawTo(h.mon, slot) else held = Held.InGame(slot)
            is Held.InGame -> if (h.slot == slot) held = null else doMove(h.slot, slot)
            null -> if (mon != null) held = Held.InGame(slot)
        }
    }

    // ---- details of a held-down Pokémon (full screen) ----
    when (val d = details) {
        is Held.InGame -> {
            val o = open
            val mon = o?.editor?.get(d.slot)
            if (o != null && mon != null) {
                BackHandler { details = null }
                PokemonDetail(
                    original = mon, editor = o.editor,
                    onDone = { edited -> details = null; transfer { it!!.editor.replace(d.slot, edited); it.write(); "Saved" } },
                    onCancel = { details = null },
                    onDelete = { details = null; transfer { it!!.editor.delete(d.slot); it.write(); "Released" } },
                    onExport = {
                        exportBytes = o.editor.exportBytes(mon)
                        exportLauncher.launch("${o.editor.data.speciesName(mon.species)}.${o.editor.fileExtension}")
                    },
                    origin = origins.describe(mon, game?.name, o.editor.fileExtension),
                    sprites = o.sprites,
                    met = com.pokeemu.app.MetInfo.describe(mon, places),
                )
                return
            }
            details = null
        }
        is Held.Stored -> {
            BackHandler { details = null }
            val from = games.firstOrNull { it.id == d.mon.fromRomId }
            val fromPlaces by produceState(emptyMap<Int, String>(), from) { value = from?.let { com.pokeemu.app.MetInfo.places(context, it) }.orEmpty() }
            val fitList by produceState<List<Transfer.Fit?>?>(null, d.mon) {
                val acc = MutableList<Transfer.Fit?>(games.size) { null }
                value = acc.toList()
                com.pokeemu.app.OpenSave.fits(context, d.mon, games) { i, fit -> acc[i] = fit; value = acc.toList() }
            }
            StoredDetail(
                s = d.mon, sprite = pc.spriteFile(d.mon), places = fromPlaces,
                fits = fitList?.let { l -> games.zip(l).mapNotNull { (g, f) -> f?.let { g to it } } },
                checking = fitList?.any { it == null } != false,
                onClose = { details = null },
                onExport = { exportBytes = d.mon.bytes; exportLauncher.launch("${d.mon.speciesName}.${d.mon.format}") },
                onRelease = {
                    details = null
                    transfer { withContext(Dispatchers.IO) { pc.remove(d.mon) }; "${displayName(d.mon.speciesName, d.mon.nickname)} was released" }
                },
            )
            return
        }
        null -> {}
    }

    BackHandler {
        when {
            held != null -> held = null
            game != null -> closeGame()
            else -> onBack()
        }
    }
    Scaffold(
        containerColor = Bg,
        topBar = {
            PokeTopBar("Pokemon PC", onBack = onBack)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- global PC: its Pokémon in pages of 30, like boxes ----
            val pages = maxOf(1, (stored.size + SLOTS) / SLOTS) // always room for one more
            val page = globalBox.coerceIn(0, pages - 1)
            val heldName = when (val h = held) {
                is Held.Stored -> displayName(h.mon.speciesName, h.mon.nickname)
                is Held.InGame -> open?.editor?.let { e -> e[h.slot]?.let { displayName(e.data.speciesName(it.species), it.nickname) } }
                null -> null
            }
            BoxPanel(
                title = { PixelText("GLOBAL PC", pixel = 2.5.dp, top = Color(0xFFD6E4FF), bottom = Color(0xFF7FA8FF)) },
                subtitle = if (held is Held.InGame) "Tap here to store $heldName" else "Box ${page + 1} of $pages · ${stored.size} stored",
                columns = 6,
                cells = SLOTS,
                onPrev = { globalBox = (page - 1 + pages) % pages },
                onNext = { globalBox = (page + 1) % pages },
                modifier = Modifier.weight(1f),
                highlight = held is Held.InGame,
            ) { i, size ->
                val s = stored.getOrNull(page * SLOTS + i)
                PcCell(
                    size, held = s != null && (held as? Held.Stored)?.mon == s,
                    onClick = { onGlobalTap(s) },
                    onLongClick = if (s != null) ({ held = null; details = Held.Stored(s) }) else null,
                ) { if (s != null) StoredSprite(s, pc.spriteFile(s), size) }
            }

            // ---- a game: pick one, then its PC ----
            Box(Modifier.weight(1.1f).fillMaxWidth()) {
                val g = game
                val o = open
                when {
                    g == null -> {
                        // Holding a stored Pokémon: say which games can take it.
                        val heldStored = (held as? Held.Stored)?.mon
                        val fits by produceState<List<Transfer.Fit?>?>(null, heldStored) {
                            value = null
                            if (heldStored == null) return@produceState
                            val acc = MutableList<Transfer.Fit?>(games.size) { null }
                            value = acc.toList()
                            com.pokeemu.app.OpenSave.fits(context, heldStored, games) { i, fit -> acc[i] = fit; value = acc.toList() }
                        }
                        GamePicker(games, cover, onPick = ::openGame, fits = fits.takeIf { heldStored != null })
                    }
                    o == null -> GamePanelFrame(g, onGames = ::closeGame) {
                        PanelNote(status ?: "", loading = status?.startsWith("Can't") != true)
                    }
                    else -> androidx.compose.runtime.key(tick) {
                        val e = o.editor
                        val party = box < 0
                        BoxPanel(
                            title = { PixelText(g.name, pixel = 2.5.dp) },
                            subtitle = if (held is Held.Stored) "Tap an empty spot to put $heldName here"
                            else if (party) "Party" else e.boxName(box),
                            columns = if (party) 3 else 6,
                            cells = if (party) 6 else e.boxSlots,
                            onPrev = { box = if (box <= -1) e.boxCount - 1 else box - 1 },
                            onNext = { box = if (box >= e.boxCount - 1) -1 else box + 1 },
                            modifier = Modifier.fillMaxSize(),
                            highlight = held is Held.Stored,
                            onGames = ::closeGame,
                        ) { i, size ->
                            val slot: Slot = if (party) Slot.Party(i) else Slot.Box(box, i)
                            val mon = if (party) e.partyMembers.getOrNull(i) else e.box(box).getOrNull(i)
                            PcCell(
                                size, held = held == Held.InGame(slot),
                                onClick = { onGameTap(slot, mon) },
                                onLongClick = if (mon != null) ({ held = null; details = Held.InGame(slot) }) else null,
                            ) { if (mon != null) GameSprite(o, mon, size, foreign = origins.isForeign(mon, g.name, e.fileExtension)) }
                        }
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
        }
    }

    confirm?.let { (s, target) ->
        val toGen5 = open?.editor?.fileExtension == "pk5"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Move ${displayName(s.speciesName, s.nickname)} into ${game?.name}?") },
            text = {
                Text(
                    if (toGen5) "Like Poké Transfer, it's met through Poké Transfer and can't go back to an older game afterwards."
                    else "Like Pal Park, it's met at Pal Park and can't go back to a GBA game afterwards.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; doWithdraw(s, target) }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private const val SLOTS = 30

// ---- game picker ------------------------------------------------------------------------

@Composable
private fun GamePicker(games: List<RomEntry>, cover: suspend (RomEntry) -> Bitmap?, onPick: (RomEntry) -> Unit, fits: List<Transfer.Fit?>? = null) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Panel)) {
        Row(Modifier.fillMaxWidth().background(PanelHeader).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelText("SELECT A GAME", pixel = 2.5.dp, top = Color(0xFFE8ECF2), bottom = Color(0xFFA8B2C2))
        }
        if (games.isEmpty()) {
            PanelNote("No game with a Pokémon save here yet. Add one to the library first.")
            return
        }
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(games.size, key = { games[it].uri.toString() }) { i -> GameCard(games[i], cover, fits?.getOrNull(i), checking = fits != null && fits.getOrNull(i) == null) { onPick(games[i]) } }
        }
    }
}

@Composable
private fun GameCard(rom: RomEntry, cover: suspend (RomEntry) -> Bitmap?, fit: Transfer.Fit?, checking: Boolean = false, onClick: () -> Unit) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, rom.uri) { value = cover(rom)?.asImageBitmap() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spot)
            .border(if (fit?.ok == true) 2.dp else 1.dp, if (fit?.ok == true) Good else SpotLine, RoundedCornerShape(12.dp))
            .graphicsLayer { alpha = if (fit?.ok == false) 0.45f else 1f }
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f).background(Color(0xFF0C0E11)), contentAlignment = Alignment.Center) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None) }
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelText(rom.name, pixel = 1.5.dp)
            if (fit != null) FitLine(fit, compact = true)
            else if (checking) Text("Checking…", color = TextDim, fontSize = 10.sp, maxLines = 1)
            else Text(Transfer.generationLabel("pk${rom.header.pokemonGen ?: 3}"), color = TextDim, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** A game's panel before its save is open (same header as the box panel). */
@Composable
private fun GamePanelFrame(rom: RomEntry, onGames: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Panel)) {
        Row(Modifier.fillMaxWidth().background(PanelHeader).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(start = 6.dp)) { PixelText(rom.name, pixel = 2.5.dp) }
            IconButton(onClick = onGames) { Icon(Icons.Default.Apps, "Choose another game", tint = TextDim) }
        }
        content()
    }
}

// ---- box panel ------------------------------------------------------------------------

/**
 * One PC box: a header (pixel-art title, page arrows; swipe the box to turn
 * the page too) over [cells] spots in [columns] columns. [highlight] marks it
 * as where the held Pokémon can go. [onGames] adds a button back to the game picker.
 */
@Composable
private fun BoxPanel(
    title: @Composable () -> Unit,
    subtitle: String,
    columns: Int,
    cells: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onGames: (() -> Unit)? = null,
    cell: @Composable (index: Int, size: Dp) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(if (highlight) 2.dp else 1.dp, if (highlight) Accent else SpotLine, shape)
            .pointerInput(subtitle) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx > 120) onPrev() else if (dx < -120) onNext() },
                ) { _, amount -> dx += amount }
            },
    ) {
        Row(Modifier.fillMaxWidth().background(PanelHeader).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ArrowButton(left = true, onClick = onPrev)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                title()
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (highlight) Accent else TextDim, fontSize = 11.sp)
            }
            ArrowButton(left = false, onClick = onNext)
            if (onGames != null) IconButton(onClick = onGames) { Icon(Icons.Default.Apps, "Choose another game", tint = TextDim) }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val rows = (cells + columns - 1) / columns
            val size = minOf(maxWidth / columns, maxHeight / rows) - 4.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (c in 0 until columns) {
                            val i = r * columns + c
                            if (i < cells) cell(i, size) else Spacer(Modifier.size(size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrowButton(left: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp)) {
            val p = Path().apply {
                if (left) { moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height) }
                else { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height) }
                close()
            }
            drawPath(p, TextMain)
        }
    }
}

/** One spot in a box; the held Pokémon bobs and glows, like in the games. Hold it for its details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcCell(size: Dp, held: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val bob = if (held) {
        val t = rememberInfiniteTransition(label = "held")
        t.animateFloat(0f, -6f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "bob").value
    } else 0f
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(size).clip(shape)
            .background(if (held) Accent.copy(alpha = 0.22f) else Spot)
            .border(if (held) 2.dp else 1.dp, if (held) Accent else SpotLine, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset(y = bob.dp)) { content() }
    }
}

@Composable
private fun PanelNote(text: String, loading: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(color = Accent)
        Text(text, textAlign = TextAlign.Center, color = TextDim, modifier = Modifier.padding(top = 12.dp))
    }
}

// ---- sprites ----------------------------------------------------------------------------

@Composable
private fun StoredSprite(s: StoredMon, file: File, size: Dp) {
    Box(Modifier.size(size)) {
        SavedSprite(file, size, fit = true)
        if (s.shiny) Text("★", Modifier.align(Alignment.TopStart).padding(3.dp), color = Accent, fontSize = 10.sp)
    }
}

@Composable
private fun GameSprite(o: OpenSave, mon: Mon, size: Dp, foreign: Boolean) {
    Box(Modifier.size(size)) {
        MonSprite(o.sprites, if (mon.isEgg) 0 else mon.species, mon.isShiny, size, Modifier.align(Alignment.Center), fit = true)
        if (mon.isShiny) Text("★", Modifier.align(Alignment.TopStart).padding(3.dp), color = Accent, fontSize = 10.sp)
        if (foreign) ForeignDot(Modifier.align(Alignment.TopEnd).padding(4.dp))
        if (mon.heldItem != 0) Box(
            Modifier.align(Alignment.BottomEnd).padding(3.dp).size(7.dp).clip(RoundedCornerShape(50)).background(TextMain),
        )
    }
}

/** A stored Pokémon's saved sprite. */
@Composable
private fun SavedSprite(file: File, size: Dp, fit: Boolean = false) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file, fit) {
        value = withContext(Dispatchers.IO) {
            val b = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@withContext null
            if (!fit) return@withContext b.asImageBitmap()
            val argb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            com.pokeemu.core.Picture(b.width, b.height, argb).trimmed()?.squared()?.let {
                Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    Box(Modifier.size(size).clipToBounds(), contentAlignment = Alignment.Center) {
        image?.let {
            // Fitted: trimmed and filling the spot. Otherwise the live sprites' scale (64 px per [size]).
            val drawn = if (fit) size * 0.9f else size * (it.width / 64f)
            Image(it, null, Modifier.requiredSize(drawn), filterQuality = FilterQuality.None)
        }
    }
}

private fun savePng(p: com.pokeemu.core.Picture, file: File) {
    val bitmap = Bitmap.createBitmap(p.argb, p.width, p.height, Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

// ---- a stored Pokémon's details -----------------------------------------------------------

/** The Pokémon record of a stored entry, for showing its stats. */
private fun parse(s: StoredMon): Mon? = runCatching {
    when (s.format) {
        "pk3" -> com.pokeemu.gen3.Pk3(s.bytes.copyOf(com.pokeemu.gen3.Pk3.BOX_SIZE))
        "pk4" -> com.pokeemu.gen4.Pk4(s.bytes.copyOf(com.pokeemu.gen4.Pk4.BOX_SIZE))
        "pk5" -> com.pokeemu.gen5.Pk5(s.bytes.copyOf(com.pokeemu.gen5.Pk5.BOX_SIZE))
        else -> null
    }
}.getOrNull()

/**
 * Everything about a Pokémon in the global PC. It's edited once it's in a game
 * (hold it there); here it can be exported to a file or released.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoredDetail(
    s: StoredMon, sprite: File, places: Map<Int, String>, fits: List<Pair<RomEntry, Transfer.Fit>>?, checking: Boolean,
    onClose: () -> Unit, onExport: () -> Unit, onRelease: () -> Unit,
) {
    val mon = remember(s) { parse(s) }
    var release by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Bg,
        topBar = {
            PokeTopBar("Details", onBack = onClose)
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(Spot), contentAlignment = Alignment.Center) {
                    SavedSprite(sprite, 96.dp, fit = true)
                }
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixelText(s.nickname.ifBlank { s.speciesName }, pixel = 2.5.dp)
                    Text(listOfNotNull(s.speciesName, s.level?.let { "Lv $it" }, if (s.shiny) "★ Shiny" else null).joinToString(" · "), color = TextMain, fontSize = 13.sp)
                    mon?.let { Text("${GameData.NATURES.getOrElse(it.nature) { "?" }} nature", color = TextDim, fontSize = 12.sp) }
                    Text(s.itemName?.let { "Holds $it" } ?: "No held item", color = TextDim, fontSize = 12.sp)
                }
            }
            DetailCard("Moves") {
                if (s.moveNames.isEmpty()) Text("—", color = TextDim)
                s.moveNames.forEach { Text(it, color = TextMain, fontSize = 14.sp) }
            }
            if (mon != null) DetailCard("Stats") {
                val labels = listOf("HP", "Attack", "Defense", "Speed", "Sp. Atk", "Sp. Def")
                Row(Modifier.fillMaxWidth()) {
                    Text("", Modifier.weight(1f))
                    Text("IV", Modifier.width(44.dp), color = TextDim, fontSize = 12.sp, textAlign = TextAlign.End)
                    Text("EV", Modifier.width(52.dp), color = TextDim, fontSize = 12.sp, textAlign = TextAlign.End)
                }
                labels.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(l, Modifier.weight(1f), color = TextMain, fontSize = 14.sp)
                        Text("${mon.iv(i)}", Modifier.width(44.dp), color = if (mon.iv(i) == 31) Accent else TextMain, fontSize = 14.sp, textAlign = TextAlign.End)
                        Text("${mon.ev(i)}", Modifier.width(52.dp), color = TextMain, fontSize = 14.sp, textAlign = TextAlign.End)
                    }
                }
            }
            DetailCard("Where it's from") {
                mon?.let { Text("OT ${it.otName.ifBlank { "—" }} · ID %05d".format(it.trainerId), color = TextMain, fontSize = 14.sp) }
                Text("Caught in ${s.originGame ?: s.fromGame}", color = TextMain, fontSize = 14.sp)
                mon?.let { com.pokeemu.app.MetInfo.describe(it, places) }?.let { Text("Met: $it", color = TextMain, fontSize = 14.sp) }
                Text(Transfer.generationLabel(s.format) + " Pokémon", color = TextDim, fontSize = 12.sp)
            }
            DetailCard("Where it can go") {
                if (fits.isNullOrEmpty() && !checking) Text("No games with a save yet.", color = TextDim, fontSize = 13.sp)
                if (checking) Text("Checking your games…", color = TextDim, fontSize = 13.sp)
                fits.orEmpty().sortedByDescending { it.second.ok }.forEach { (rom, fit) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(rom.name, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        FitLine(fit)
                    }
                }
            }
            Text("To edit it, move it into a game and hold it there.", color = TextDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onExport, Modifier.weight(1f)) { Text("Export file") }
                OutlinedButton(onClick = { release = true }, Modifier.weight(1f)) { Text("Release", color = Color(0xFFFF8A80)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (release) AlertDialog(
        onDismissRequest = { release = false },
        title = { Text("Release ${displayName(s.speciesName, s.nickname)}?") },
        text = { Text("It's removed from the global PC for good. Export it to a file first if you might want it back.") },
        confirmButton = { TextButton(onClick = { release = false; onRelease() }) { Text("Release") } },
        dismissButton = { TextButton(onClick = { release = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

private fun displayName(species: String, nickname: String) =
    if (nickname.isBlank() || nickname.equals(species, ignoreCase = true)) species else "$nickname ($species)"

/** "✓ Fits as it is" or "✗ Zekrom isn't in this game". */
@Composable
private fun FitLine(fit: Transfer.Fit, compact: Boolean = false) {
    Text(
        (if (fit.ok) "✓ " else "✗ ") + fit.message,
        color = if (fit.ok) Good else Bad, fontSize = if (compact) 10.sp else 12.sp,
        maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis,
    )
}
