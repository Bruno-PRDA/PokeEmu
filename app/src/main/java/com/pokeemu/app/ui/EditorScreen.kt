package com.pokeemu.app.ui

import com.pokeemu.core.Slot
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.app.OpenSave
import com.pokeemu.app.RomEntry
import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.saves.SaveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(rom: RomEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>("Reading ROM…") }
    var editor by remember { mutableStateOf<MonEditor?>(null) }
    var manager by remember { mutableStateOf<SaveManager?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var box by remember { mutableIntStateOf(0) }
    var moving by remember { mutableStateOf<Slot?>(null) }
    var detail by remember { mutableStateOf<Slot?>(null) }
    var importTarget by remember { mutableStateOf<Slot?>(null) }
    var exportSlot by remember { mutableStateOf<Slot?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    fun act(block: (MonEditor) -> Unit) {
        val e = editor ?: return
        try { block(e) } catch (ex: IllegalArgumentException) { toast(ex.message ?: "Not allowed") }
        tick++
    }

    suspend fun load() {
        val opened = OpenSave.open(context, rom) { status = it }
        editor = opened.editor; manager = opened.manager; status = null
    }

    LaunchedEffect(rom.uri) {
        try { load() } catch (ex: Exception) { status = "Can't edit this save: ${ex.message}" }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val pk = exportSlot?.let { editor?.get(it) }
        if (uri != null && pk != null) {
            runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(editor!!.exportBytes(pk)) } }
                .onSuccess { toast("Exported") }.onFailure { toast("Export failed: ${it.message}") }
        }
        exportSlot = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = importTarget
        importTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            ?: run { toast("Couldn't read that file"); return@rememberLauncherForActivityResult }
        act { it.insert(target, it.parseImport(bytes)) }
    }

    fun save() {
        val e = editor ?: return
        val m = manager ?: return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Verify: the written file must parse and contain exactly the edited party.
                    m.applyEdit(e.toBytes(), e::verify)
                }
                toast("Saved. A backup of the previous save was kept.")
                load()
            } catch (ex: Exception) {
                toast("Not saved: ${ex.message}")
            }
            saving = false
            tick++
        }
    }

    val dirty = editor?.dirty == true
    BackHandler(enabled = detail == null) { if (dirty) confirmDiscard = true else onBack() }

    // ---- detail page ----
    val e = editor
    val detailSlot = detail
    if (e != null && detailSlot != null) {
        val pk = e[detailSlot]
        if (pk != null) {
            BackHandler { detail = null }
            PokemonDetail(
                original = pk, editor = e,
                onDone = { edited -> act { it.replace(detailSlot, edited) }; detail = null },
                onCancel = { detail = null },
                onDelete = { act { it.delete(detailSlot) }; detail = null },
                onExport = { exportSlot = detailSlot; exportLauncher.launch("${e.data.speciesName(pk.species)}.${e.fileExtension}") },
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Pokémon") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (e != null) IconButton(onClick = { showReport = true }) { Icon(Icons.Default.Info, "ROM data") }
                    TextButton(onClick = ::save, enabled = dirty && !saving) { Text(if (saving) "Saving…" else "Save") }
                },
            )
        },
    ) { padding ->
        if (e == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (status?.startsWith("Can't") != true) CircularProgressIndicator()
                    Text(status ?: "", textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                }
            }
            return@Scaffold
        }
        tick.let { } // recompose after editor mutations

        fun onCell(slot: Slot) {
            val from = moving
            when {
                from != null -> { moving = null; act { it.move(from, slot) } }
                e[slot] != null -> detail = slot
                else -> { importTarget = slot; importLauncher.launch(arrayOf("*/*")) }
            }
        }

        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            moving?.let { m ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Moving ${e[m]?.let { e.data.speciesName(it.species) }} — tap where to put it", Modifier.weight(1f))
                        TextButton(onClick = { moving = null }) { Text("Cancel") }
                    }
                }
            }
            Text("Party", style = MaterialTheme.typography.titleMedium)
            Grid(columns = 3, cells = 6) { i ->
                val slot = Slot.Party(i)
                Cell(e, e.partyMembers.getOrNull(i), selected = moving == slot, onClick = { onCell(slot) }, onLongClick = { moving = slot })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { box = (box + e.boxCount - 1) % e.boxCount }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous box") }
                Text(e.boxName(box), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { box = (box + 1) % e.boxCount }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next box") }
            }
            val contents = e.box(box)
            Grid(columns = 6, cells = e.boxSlots) { i ->
                val slot = Slot.Box(box, i)
                Cell(e, contents[i], selected = moving == slot, compact = true, onClick = { onCell(slot) }, onLongClick = { moving = slot })
            }
            Text("Tap to edit · long-press to move · tap an empty slot to import a .${e.fileExtension}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard changes?") },
        text = { Text("Your edits haven't been saved.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
    if (showReport && e != null) AlertDialog(
        onDismissRequest = { showReport = false },
        title = { Text("Game data from ROM") },
        text = { Text(e.data.report.joinToString("\n\n")) },
        confirmButton = { TextButton(onClick = { showReport = false }) { Text("OK") } },
    )
}

@Composable
private fun Grid(columns: Int, cells: Int, content: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until (cells + columns - 1) / columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until columns) {
                    val i = row * columns + col
                    Box(Modifier.weight(1f)) { if (i < cells) content(i) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@NonSkippableComposable // warnings depend on SaveEditor state
@Composable
private fun Cell(e: MonEditor, pk: Mon?, selected: Boolean, compact: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    val warn = pk != null && e.warnings(pk).isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 1f else 2.2f)
            .combinedClickable(onClick = onClick, onLongClick = if (pk != null) onLongClick else null),
        border = when {
            selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            warn -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            else -> null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (pk == null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            if (pk != null) {
                val name = if (pk.isEgg) "Egg" else e.data.speciesName(pk.species)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        name + if (pk.isShiny) "★" else "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = if (compact) 10.sp else 14.sp, textAlign = TextAlign.Center,
                    )
                    e.level(pk)?.let { Text("Lv$it", fontSize = if (compact) 9.sp else 12.sp) }
                }
            }
        }
    }
}
