package com.pokeemu.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.pokeemu.app.ui.CheatsScreen
import com.pokeemu.app.ui.ChallengesScreen
import com.pokeemu.saves.ChallengeStore
import com.pokeemu.app.ui.EditorScreen
import com.pokeemu.app.ui.GlobalPcScreen
import com.pokeemu.app.ui.LibraryScreen
import com.pokeemu.app.ui.PokeEmuTheme
import com.pokeemu.app.ui.SaveManagerScreen
import com.pokeemu.saves.CheatStore
import com.pokeemu.saves.GameStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var library: RomLibrary
    private var roms by mutableStateOf<List<RomEntry>>(emptyList())
    private var busy by mutableStateOf<String?>(null)
    private var managingSaves by mutableStateOf<RomEntry?>(null)
    private var editing by mutableStateOf<RomEntry?>(null)
    private var globalPc by mutableStateOf(false)
    /** Bumped to make game tiles reload their pictures. */
    private var coverVersion by mutableStateOf(0)
    private var coverFor: RomEntry? = null

    /** "Change cover picture": any image on the phone (box art, a screenshot...). */
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val rom = coverFor ?: return@registerForActivityResult
        coverFor = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: error("not an image")
                    library.setCover(rom, bitmap)
                }.isSuccess
            }
            if (!ok) Toast.makeText(this@MainActivity, "That file isn't a picture", Toast.LENGTH_SHORT).show()
            coverVersion++
        }
    }
    /** The game whose cheat list is open, with its ROM id (where its cheats are stored). */
    private var cheatsFor by mutableStateOf<Pair<RomEntry, String>?>(null)
    private var challengesFor by mutableStateOf<Pair<RomEntry, String>?>(null)

    /** "+": pick one or more files anywhere (Downloads, …); each is copied into the app. */
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) importFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = RomLibrary(this)
        setContent {
            PokeEmuTheme {
                LaunchedEffect(Unit) { start() }
                editing?.let { rom ->
                    EditorScreen(rom, onBack = { editing = null })
                    return@PokeEmuTheme
                }
                challengesFor?.let { (rom, id) ->
                    ChallengesScreen(title = rom.name, store = ChallengeStore(GameStorage(filesDir), id), onBack = { challengesFor = null })
                    return@PokeEmuTheme
                }
                cheatsFor?.let { (rom, id) ->
                    CheatsScreen(
                        title = rom.name,
                        store = CheatStore(GameStorage(filesDir), id),
                        platform = rom.header.platform,
                        onBack = { cheatsFor = null },
                        evolutionChanges = if (!rom.header.liveTools) null else suspend {
                            val file = withContext(Dispatchers.IO) { library.localCopy(rom.uri).file }
                            EvolutionCheat.changes(this@MainActivity, file, id, rom.header.platform == com.pokeemu.saves.Platform.NDS).map { it.second }.distinct()
                        },
                    )
                    return@PokeEmuTheme
                }
                if (globalPc) {
                    GlobalPcScreen(roms.filter { it.header.pokemonGen in GlobalPcGames }, onBack = { globalPc = false })
                    return@PokeEmuTheme
                }
                managingSaves?.let { rom ->
                    BackHandler { managingSaves = null }
                    // key: re-read the save summary when coming back from the editor
                    key(rom, editing) {
                        SaveManagerScreen(rom, onBack = { managingSaves = null }, onEditPokemon = { editing = rom })
                    }
                    return@PokeEmuTheme
                }
                LibraryScreen(
                    roms = roms,
                    busy = busy,
                    icon = library::icon,
                    cover = { rom ->
                        withContext(Dispatchers.IO) { library.coverFile(rom)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }
                    },
                    coverVersion = coverVersion,
                    hasCustomCover = library::hasCustomCover,
                    onChangeCover = { rom -> coverFor = rom; pickCover.launch("image/*") },
                    onRemoveCover = { rom ->
                        lifecycleScope.launch { withContext(Dispatchers.IO) { library.removeCover(rom) }; coverVersion++ }
                    },
                    onAdd = { pickFiles.launch(arrayOf("*/*")) },
                    onImportFolder = { pickFolder.launch(null) },
                    onGlobalPc = { globalPc = true },
                    onOpen = { rom -> startActivity(PlayerActivity.intent(this, rom.uri, rom.fileName)) },
                    onManageSaves = { managingSaves = it },
                    onEditPokemon = { editing = it },
                    onChallenges = { rom ->
                        lifecycleScope.launch {
                            val id = withContext(Dispatchers.IO) { library.localCopy(rom.uri).id }
                            challengesFor = rom to id
                        }
                    },
                    onCheats = { rom ->
                        lifecycleScope.launch {
                            val id = withContext(Dispatchers.IO) { library.localCopy(rom.uri).id }
                            cheatsFor = rom to id
                        }
                    },
                    onRename = { rom, name ->
                        lifecycleScope.launch {
                            library.rename(rom, name)
                            roms = library.games()
                        }
                    },
                    onRemove = { rom ->
                        lifecycleScope.launch {
                            library.remove(rom)
                            roms = library.games()
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        coverVersion++ // back from a game: its last-played screen or new cover may have changed
    }

    private suspend fun start() {
        roms = library.games()
        // Older versions played games from a folder in place: bring those games into the app once.
        library.legacyFolder?.let { folder ->
            runImport("Moving your games into the app…") { library.importFolder(folder) { busy = "Moving $it into the app…" } }
            library.legacyFolder = null
            File(filesDir, "rom-cache").deleteRecursively()
        }
    }

    private fun importFiles(uris: List<Uri>) = lifecycleScope.launch {
        var added = 0
        val problems = mutableListOf<String>()
        runImport("Adding game…") {
            for ((i, uri) in uris.withIndex()) {
                busy = if (uris.size > 1) "Adding game ${i + 1} of ${uris.size}…" else "Adding game…"
                when (val r = library.import(uri)) {
                    is RomLibrary.ImportResult.Added -> added++
                    is RomLibrary.ImportResult.AlreadyThere -> problems += "${r.entry.name} is already in your games"
                    RomLibrary.ImportResult.NotARom -> problems += "That file isn't a GBA or DS game"
                    is RomLibrary.ImportResult.Failed -> problems += r.message
                }
            }
            added
        }
        val message = buildList {
            if (added > 0) add(if (added == 1) "Game added. You can delete the downloaded file now." else "$added games added.")
            addAll(problems.distinct())
        }.joinToString("\n")
        if (message.isNotEmpty()) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
    }

    private fun importFolder(tree: Uri) = lifecycleScope.launch {
        val added = runImport("Looking for games…") { library.importFolder(tree) { busy = "Adding $it…" } }
        Toast.makeText(
            this@MainActivity,
            when (added) { 0 -> "No new games found in that folder."; 1 -> "1 game added."; else -> "$added games added." },
            Toast.LENGTH_LONG,
        ).show()
    }

    private suspend fun runImport(label: String, block: suspend () -> Int): Int {
        busy = label
        return try {
            block()
        } finally {
            busy = null
            roms = library.games()
        }
    }
}

/** Generations the Global PC can read and write saves of. */
val GlobalPcGames = setOf(3, 4)
