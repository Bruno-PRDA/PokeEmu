package com.pokeemu.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.app.OpenSave
import com.pokeemu.app.RomEntry
import com.pokeemu.core.GlobalPc
import com.pokeemu.core.Mon
import com.pokeemu.core.Slot
import com.pokeemu.core.StoredMon
import com.pokeemu.core.Transfer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the player is holding, like the "Move" hand of the in-game PC. */
private sealed interface Held {
    data class Stored(val mon: StoredMon) : Held
    data class InGame(val slot: Slot) : Held
}

/**
 * The global Pokémon PC, laid out like the games' own PC: the global boxes on
 * top, one game's boxes (or party) below, each with a box wallpaper and a name
 * tab. Tap a Pokémon to pick it up, then tap where it goes: into a game's box,
 * into the global PC, or another spot in the same game. Each move is written to
 * the game's save at once (with a backup of the save as it was).
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
    var globalBox by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Held?>(null) }
    /** A withdrawal that changes the Pokémon's generation, waiting for a yes. */
    var confirm by remember { mutableStateOf<Pair<StoredMon, Slot>?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

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

    /** Runs a move; on failure the save is re-read so the screen matches the file. */
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
            held = null
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
        globalBox = (stored.size) / SLOTS // show where it landed
        "$name moved to the global PC"
    }

    fun doWithdraw(s: StoredMon, target: Slot) = transfer { o ->
        val e = o.editor
        val result = Transfer.adapt(s, e, o.romId)
        e.insert(target, result.mon)
        o.write()
        withContext(Dispatchers.IO) { pc.remove(s) } // only once the game has it
        val where = if (target is Slot.Box) e.boxName(target.box) else "the party"
        (listOf("${displayName(s.speciesName, s.nickname)} moved into $where") + result.notes).joinToString("\n")
    }

    fun doMove(from: Slot, to: Slot) = transfer { o ->
        o.editor.move(from, to)
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
            is Held.InGame -> when {
                h.slot == slot -> held = null
                else -> doMove(h.slot, slot)
            }
            null -> if (mon != null) held = Held.InGame(slot)
        }
    }

    BackHandler { if (held != null) held = null else onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pokémon PC") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoStrip(held, stored, open, game, origins, pc)

            // ---- global PC: its Pokémon in pages of 30, like boxes ----
            val pages = maxOf(1, (stored.size + SLOTS) / SLOTS) // always room for one more
            val page = globalBox.coerceIn(0, pages - 1)
            BoxPanel(
                title = "GLOBAL BOX ${page + 1}",
                subtitle = "${stored.size} stored",
                wallpaper = GLOBAL_WALLPAPER,
                columns = 6,
                cells = SLOTS,
                onPrev = { globalBox = (page - 1 + pages) % pages },
                onNext = { globalBox = (page + 1) % pages },
                modifier = Modifier.weight(1f),
                highlight = held is Held.InGame,
            ) { i, size ->
                val s = stored.getOrNull(page * SLOTS + i)
                PcCell(size, held = s != null && (held as? Held.Stored)?.mon == s, onClick = { onGlobalTap(s) }) {
                    if (s != null) StoredSprite(s, pc.spriteFile(s), size)
                }
            }

            // ---- one game ----
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                items(games, key = { it.uri.toString() }) { rom ->
                    FilterChip(selected = game == rom, onClick = { if (game != rom) openGame(rom) }, label = { Text(rom.name, maxLines = 1) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val o = open
                when {
                    game == null -> PanelNote("Pick a game to move Pokémon in or out of it.")
                    o == null -> PanelNote(status ?: "", loading = status?.startsWith("Can't") != true)
                    else -> androidx.compose.runtime.key(tick) {
                        val e = o.editor
                        val party = box < 0
                        BoxPanel(
                            title = if (party) "PARTY" else e.boxName(box).uppercase(),
                            subtitle = game!!.name,
                            wallpaper = if (party) PARTY_WALLPAPER else WALLPAPERS[box % WALLPAPERS.size],
                            columns = if (party) 3 else 6,
                            cells = if (party) 6 else e.boxSlots,
                            onPrev = { box = if (box <= -1) e.boxCount - 1 else box - 1 },
                            onNext = { box = if (box >= e.boxCount - 1) -1 else box + 1 },
                            modifier = Modifier.fillMaxSize(),
                            highlight = held is Held.Stored,
                        ) { i, size ->
                            val slot: Slot = if (party) Slot.Party(i) else Slot.Box(box, i)
                            val mon = if (party) e.partyMembers.getOrNull(i) else e.box(box).getOrNull(i)
                            PcCell(size, held = held == Held.InGame(slot), onClick = { onGameTap(slot, mon) }) {
                                if (mon != null) GameSprite(o, mon, size, foreign = origins.isForeign(mon, game!!.name, e.fileExtension))
                            }
                        }
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
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

// ---- box panel ------------------------------------------------------------------------

/** Box wallpaper colours: top, bottom, pattern. */
private class Wallpaper(val top: Color, val bottom: Color, val pattern: Color)

private val GLOBAL_WALLPAPER = Wallpaper(Color(0xFF5E4FA8), Color(0xFF2E2A6B), Color(0x33FFFFFF))
private val PARTY_WALLPAPER = Wallpaper(Color(0xFFE85A4F), Color(0xFFA8323A), Color(0x33FFFFFF))
private val WALLPAPERS = listOf(
    Wallpaper(Color(0xFF6FBF5A), Color(0xFF3E8A3A), Color(0x2EFFFFFF)), // forest
    Wallpaper(Color(0xFF8FA3B8), Color(0xFF56677A), Color(0x2EFFFFFF)), // city
    Wallpaper(Color(0xFFE8C36A), Color(0xFFC08A3A), Color(0x33FFFFFF)), // desert
    Wallpaper(Color(0xFFB8D86A), Color(0xFF7FA03A), Color(0x2EFFFFFF)), // savanna
    Wallpaper(Color(0xFFA88A6A), Color(0xFF6E5440), Color(0x2EFFFFFF)), // crag
    Wallpaper(Color(0xFFE8784F), Color(0xFF9E3A2E), Color(0x33FFE0A0)), // volcano
    Wallpaper(Color(0xFFDDEEF8), Color(0xFF9FC4DE), Color(0x66FFFFFF)), // snow
    Wallpaper(Color(0xFF6A6A8A), Color(0xFF3A3A55), Color(0x2EFFFFFF)), // cave
    Wallpaper(Color(0xFFF2DFA0), Color(0xFF6FC6E0), Color(0x40FFFFFF)), // beach
    Wallpaper(Color(0xFF3E7FC6), Color(0xFF1E3E7A), Color(0x33A0E0FF)), // seafloor
    Wallpaper(Color(0xFF5FB8C8), Color(0xFF2E7A8A), Color(0x33FFFFFF)), // river
    Wallpaper(Color(0xFF8FD0F0), Color(0xFF4F98D8), Color(0x55FFFFFF)), // sky
)

/**
 * One PC box: a name tab with arrows (swipe the box to turn the page too) over
 * a wallpaper with [cells] spots in [columns] columns. [highlight] marks it as
 * where the held Pokémon can go.
 */
@Composable
private fun BoxPanel(
    title: String,
    subtitle: String,
    wallpaper: Wallpaper,
    columns: Int,
    cells: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    cell: @Composable (index: Int, size: Dp) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (highlight) 3.dp else 2.dp, if (highlight) Color(0xFFFFD54F) else Color(0x66000000), shape)
            .pointerInput(title) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx > 120) onPrev() else if (dx < -120) onNext() },
                ) { _, amount -> dx += amount }
            },
    ) {
        // Name tab
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(wallpaper.top, wallpaper.bottom)))
                .background(Color(0x55000000)).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowButton(left = true, onClick = onPrev)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                        shadow = Shadow(Color(0xCC000000), Offset(2f, 2f), 0f),
                    ),
                )
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xDDFFFFFF), fontSize = 10.sp)
            }
            ArrowButton(left = false, onClick = onNext)
        }
        // Wallpaper and spots
        BoxWithConstraints(Modifier.fillMaxSize()) {
            WallpaperCanvas(wallpaper)
            val rows = (cells + columns - 1) / columns
            val size = minOf(maxWidth / columns, maxHeight / rows) - 2.dp
            Column(
                Modifier.fillMaxSize().padding(2.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
private fun WallpaperCanvas(w: Wallpaper) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(w.top, w.bottom)))
        // A diamond pattern, like the games' box wallpapers.
        val step = 28.dp.toPx()
        val r = 5.dp.toPx()
        var y = step / 2
        var row = 0
        while (y < size.height + step) {
            var x = if (row % 2 == 0) step / 2 else step
            while (x < size.width + step) {
                val p = Path().apply {
                    moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close()
                }
                drawPath(p, w.pattern)
                x += step
            }
            y += step / 2; row++
        }
    }
}

@Composable
private fun ArrowButton(left: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val p = Path().apply {
                if (left) { moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height) }
                else { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height) }
                close()
            }
            drawPath(p, Color.White)
        }
    }
}

/** One spot in a box; the held Pokémon bobs and glows, like in the games. */
@Composable
private fun PcCell(size: Dp, held: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val bob = if (held) {
        val t = rememberInfiniteTransition(label = "held")
        t.animateFloat(0f, -6f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "bob").value
    } else 0f
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp))
            .background(if (held) Color(0x66FFD54F) else Color(0x1FFFFFFF))
            .then(if (held) Modifier.border(2.dp, Color(0xFFFFD54F), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset(y = bob.dp)) { content() }
    }
}

@Composable
private fun PanelNote(text: String, loading: Boolean = false) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator()
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

// ---- sprites ----------------------------------------------------------------------------

@Composable
private fun StoredSprite(s: StoredMon, file: File, size: Dp) {
    Box(Modifier.size(size)) {
        SavedSprite(file, size, fit = true)
        if (s.shiny) Text("★", Modifier.align(Alignment.TopStart).padding(2.dp), color = Color(0xFFFFE082), fontSize = 10.sp)
    }
}

@Composable
private fun GameSprite(o: OpenSave, mon: Mon, size: Dp, foreign: Boolean) {
    Box(Modifier.size(size)) {
        MonSprite(o.sprites, if (mon.isEgg) 0 else mon.species, mon.isShiny, size, Modifier.align(Alignment.Center), fit = true)
        if (mon.isShiny) Text("★", Modifier.align(Alignment.TopStart).padding(2.dp), color = Color(0xFFFFE082), fontSize = 10.sp)
        if (foreign) ForeignDot(Modifier.align(Alignment.TopEnd).padding(3.dp))
        if (mon.heldItem != 0) Box(
            Modifier.align(Alignment.BottomEnd).padding(2.dp).size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFFFFF))
                .border(1.dp, Color(0xFF555555), RoundedCornerShape(50)),
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
                android.graphics.Bitmap.createBitmap(it.argb, it.width, it.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
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
    val bitmap = android.graphics.Bitmap.createBitmap(p.argb, p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}

// ---- info strip -------------------------------------------------------------------------

/** The held Pokémon's details (the left panel of the in-game PC), or what to do. */
@Composable
private fun InfoStrip(held: Held?, stored: List<StoredMon>, open: OpenSave?, game: RomEntry?, origins: Origins, pc: GlobalPc) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF263238), Color(0xFF37474F))))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            when (held) {
                is Held.Stored -> SavedSprite(pc.spriteFile(held.mon), 76.dp, fit = true)
                is Held.InGame -> open?.editor?.get(held.slot)?.let { MonSprite(open.sprites, if (it.isEgg) 0 else it.species, it.isShiny, 76.dp, fit = true) }
                null -> Text("PC", color = Color(0x88FFFFFF), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val white = Color.White
            val dim = Color(0xBBFFFFFF)
            when (held) {
                is Held.Stored -> {
                    val s = held.mon
                    Text(s.nickname.ifBlank { s.speciesName } + if (s.shiny) " ★" else "", color = white, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(s.speciesName, s.level?.let { "Lv $it" }, s.itemName?.let { "holds $it" }).joinToString(" · "), color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "from ${s.fromGame} · ${Transfer.generationLabel(s.format)}" +
                            (s.originGame?.takeIf { Transfer.normalize(it) != Transfer.normalize(s.fromGame) }?.let { " · caught in $it" } ?: ""),
                        color = dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Hint(if (open == null) "Pick a game below, then tap an empty spot in it" else "Tap an empty spot in ${game?.name} to put it there")
                }
                is Held.InGame -> {
                    val e = open?.editor
                    val mon = e?.get(held.slot)
                    if (e != null && mon != null) {
                        val species = e.data.speciesName(mon.species)
                        Text(displayName(species, mon.nickname) + if (mon.isShiny) " ★" else "", color = white, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listOfNotNull(e.level(mon)?.let { "Lv $it" }, mon.heldItem.takeIf { it != 0 }?.let { "holds ${e.data.itemName(it)}" }).joinToString(" · "),
                                color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (mon.heldItem != 0) ItemIcon(open.sprites, mon.heldItem, 20.dp, Modifier.padding(start = 4.dp))
                        }
                        origins.describe(mon, game?.name, e.fileExtension)?.let {
                            Text("origin: $it", color = dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Hint("Tap the global PC to store it, or another spot to move it")
                }
                null -> {
                    Text("Global PC", color = white, fontWeight = FontWeight.Bold)
                    Text("${stored.size} Pokémon stored, shared by all your games", color = dim, fontSize = 12.sp)
                    Hint("Tap a Pokémon to pick it up, then tap where it goes")
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)

private fun displayName(species: String, nickname: String) =
    if (nickname.isBlank() || nickname.equals(species, ignoreCase = true)) species else "$nickname ($species)"
