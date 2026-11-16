package com.pokeemu.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.pokeemu.app.ui.EditorScreen
import com.pokeemu.app.ui.LibraryScreen
import com.pokeemu.app.ui.PokeEmuTheme
import com.pokeemu.app.ui.SaveManagerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var library: RomLibrary
    private var roms by mutableStateOf<List<RomEntry>>(emptyList())
    private var loading by mutableStateOf(false)
    private var hasFolder by mutableStateOf(false)
    private var managingSaves by mutableStateOf<RomEntry?>(null)
    private var editing by mutableStateOf<RomEntry?>(null)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            library.folder = uri
            hasFolder = true
            rescan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = RomLibrary(this)
        hasFolder = library.folder != null
        setContent {
            PokeEmuTheme {
                LaunchedEffect(Unit) { if (hasFolder) rescan() }
                editing?.let { rom ->
                    EditorScreen(rom, onBack = { editing = null })
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
                    hasFolder = hasFolder,
                    loading = loading,
                    onPickFolder = { pickFolder.launch(library.folder) },
                    onRefresh = ::rescan,
                    onOpen = { rom -> startActivity(PlayerActivity.intent(this, rom.uri, rom.fileName)) },
                    onManageSaves = { managingSaves = it },
                )
            }
        }
    }

    private fun rescan() {
        lifecycleScope.launch {
            loading = true
            roms = library.scan()
            loading = false
        }
    }
}
