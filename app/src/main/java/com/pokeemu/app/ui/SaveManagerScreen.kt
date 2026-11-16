package com.pokeemu.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pokeemu.app.RomEntry
import com.pokeemu.app.RomLibrary
import com.pokeemu.gen3.Gen3Save
import com.pokeemu.gen3.Gen3SaveException
import com.pokeemu.saves.GameStorage
import com.pokeemu.saves.SaveManager
import com.pokeemu.saves.romId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** What the screen shows; rebuilt after every operation. */
private data class SaveState(
    val slots: List<String>,
    val activeSlot: String,
    val backups: List<SaveManager.Backup>,
    val hasSave: Boolean,
    val summary: Result<Gen3Save>?,
)

/** A confirmation the user must accept before something replaces their save. */
private data class Confirm(val title: String, val text: String, val action: suspend () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveManagerScreen(rom: RomEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manager by remember { mutableStateOf<SaveManager?>(null) }
    var state by remember { mutableStateOf<SaveState?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var newSlotDialog by remember { mutableStateOf(false) }
    var renameSlot by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    suspend fun refresh() {
        val m = manager ?: return
        state = withContext(Dispatchers.IO) {
            SaveState(
                slots = m.slots(),
                activeSlot = m.activeSlot,
                backups = m.backups(),
                hasSave = m.hasSave,
                summary = if (m.hasSave) runCatching { Gen3Save.parse(m.saveFile.readBytes()) } else null,
            )
        }
    }

    /** Runs [block] off the main thread, reports errors, then refreshes. */
    fun run(success: String? = null, block: suspend (SaveManager) -> Unit) {
        val m = manager ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block(m) }
                success?.let(::toast)
            } catch (e: Exception) {
                toast(e.message ?: "Something went wrong")
            }
            refresh()
        }
    }

    LaunchedEffect(rom.uri) {
        val id = withContext(Dispatchers.IO) { romId(RomLibrary(context).readRom(rom.uri)) }
        manager = SaveManager(GameStorage(context.filesDir), id)
        refresh()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) run("Save exported") { m ->
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(m.saveFile.readBytes()) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirm = Confirm(
            "Import this save?",
            "It replaces the save in slot \"${state?.activeSlot}\". The current save is backed up first.",
        ) {
            run("Save imported") { m ->
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                m.import(bytes)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rom.fileName.substringBeforeLast('.')) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(s) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import .sav") }
                    FilledTonalButton(onClick = { exportLauncher.launch("${rom.fileName.substringBeforeLast('.')}.sav") }, enabled = s.hasSave) {
                        Text("Export .sav")
                    }
                }
            }

            item { SectionTitle("Save slots") }
            items(s.slots, key = { "slot-$it" }) { slot ->
                val active = slot == s.activeSlot
                ListItem(
                    leadingContent = {
                        RadioButton(selected = active, onClick = {
                            if (!active) confirm = Confirm(
                                "Switch to \"$slot\"?",
                                "Your current save stays in \"${s.activeSlot}\" and you can switch back any time. Each slot has its own save states.",
                            ) { run("Switched to $slot") { it.switchTo(slot) } }
                        })
                    },
                    headlineContent = { Text(slot) },
                    supportingContent = { if (active) Text("Active") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { renameSlot = slot }) { Text("Rename") }
                            if (!active) IconButton(onClick = {
                                confirm = Confirm("Delete slot \"$slot\"?", "A backup of its save is kept in the backups list.") {
                                    run("Slot deleted") { it.deleteSlot(slot) }
                                }
                            }) { Icon(Icons.Default.Delete, "Delete slot") }
                        }
                    },
                )
            }
            item { OutlinedButton(onClick = { newSlotDialog = true }) { Text("New slot") } }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Backups", Modifier.weight(1f))
                    TextButton(onClick = { run("Backup created") { m -> m.backup(SaveManager.REASON_MANUAL) ?: error("Nothing new to back up") } }, enabled = s.hasSave) {
                        Text("Back up now")
                    }
                }
            }
            if (s.backups.isEmpty()) item { Text("No backups yet. One is made automatically every day you play and before every change.", style = MaterialTheme.typography.bodyMedium) }
            items(s.backups, key = { it.file.name }) { b -> BackupRow(b, onRestore = {
                confirm = Confirm(
                    "Restore this backup?",
                    "It replaces the save in slot \"${s.activeSlot}\". The current save is backed up first.\n\n" +
                        "Tip: don't load save states made before restoring — saving in-game afterwards would bring the old save back.",
                ) { run("Backup restored") { it.restore(b) } }
            }, onPin = { run { it.setPinned(b, !b.pinned) } }, onDelete = {
                confirm = Confirm("Delete this backup?", "This can't be undone.") { run { it.delete(b) } }
            }) }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { c.action() } }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    if (newSlotDialog) NewSlotDialog(
        onDismiss = { newSlotDialog = false },
        onCreate = { name, copy -> newSlotDialog = false; run("Slot \"$name\" created") { it.createSlot(name, copy) } },
    )
    renameSlot?.let { old ->
        NameDialog("Rename slot", old, onDismiss = { renameSlot = null }) { new ->
            renameSlot = null
            run { it.renameSlot(old, new) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(s: SaveState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val summary = s.summary
            when {
                !s.hasSave -> Text("No save yet — play the game and save in-game.")
                summary == null -> Unit
                summary.isFailure -> {
                    Text("Not a readable Gen 3 save", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (summary.exceptionOrNull() as? Gen3SaveException)?.message ?: "Unknown format",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Slots, backups and import/export still work.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val save = summary.getOrThrow()
                    val t = save.trainer
                    Text(t.name.ifEmpty { "(no name)" }, style = MaterialTheme.typography.titleLarge)
                    Text("${save.game.label} · ID %05d".format(t.trainerId))
                    Text("Played %d:%02d · ₽%,d".format(t.hours, t.minutes, save.money))
                    if (save.party.isNotEmpty()) {
                        Text("Party", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (pk in save.party) {
                                AssistChip(onClick = {}, label = {
                                    Text(buildString {
                                        append(if (pk.isEgg) "Egg" else pk.nickname)
                                        pk.level?.let { append(" Lv$it") }
                                        if (pk.isShiny) append(" ★")
                                    })
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupRow(b: SaveManager.Backup, onRestore: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ListItem(
        headlineContent = { Text(format.format(Date(b.createdAt))) },
        supportingContent = { Text(b.reason.replace('-', ' ').replaceFirstChar { it.uppercase() } + if (b.pinned) " · kept forever" else "") },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) { Icon(Icons.Default.Restore, "Restore") }
                IconButton(onClick = onPin) { Icon(if (b.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (b.pinned) "Unpin" else "Pin") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        },
    )
}

@Composable
private fun NewSlotDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New save slot") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !copy, onClick = { copy = false }); Text("Empty (new game)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = copy, onClick = { copy = true }); Text("Copy of current save")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, copy) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
