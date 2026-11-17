package com.pokeemu.app

import android.content.Context
import com.pokeemu.core.MonEditor
import com.pokeemu.gen3.Gen3Save
import com.pokeemu.gen3.SaveEditor
import com.pokeemu.gen4.Gen4Save
import com.pokeemu.gen4.Gen4SaveEditor
import com.pokeemu.saves.GameStorage
import com.pokeemu.saves.Platform
import com.pokeemu.saves.SaveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A game's save opened for editing: the editor, the manager that writes it back, and the ROM id. */
class OpenSave(val editor: MonEditor, val manager: SaveManager, val romId: String) {
    /** Writes the editor's changes to the save (backing up the old one first, and verifying the result). */
    suspend fun write() = withContext(Dispatchers.IO) { manager.applyEdit(editor.toBytes(), editor::verify) }

    companion object {
        /**
         * Reads the ROM's names/data (cached after the first time) and parses the
         * save. Throws with a message for the user if either can't be read.
         */
        suspend fun open(context: Context, rom: RomEntry, onStatus: (String) -> Unit = {}): OpenSave =
            withContext(Dispatchers.IO) {
                val local = RomLibrary(context).localCopy(rom.uri)
                val id = local.id
                val storage = GameStorage(context.filesDir)
                val store = RomDataStore(storage)
                val manager = SaveManager(storage, id)
                check(manager.hasSave) { "this game has no save yet. Play it and save in-game first" }
                val editor: MonEditor = if (rom.header.platform == Platform.NDS) {
                    withContext(Dispatchers.Main) { onStatus("Reading game data from the ROM…") }
                    val data = store.gen4(id, local.file) ?: throw IllegalStateException("couldn't read game data from this ROM")
                    Gen4SaveEditor(Gen4Save.parse(manager.saveFile.readBytes()), data)
                } else {
                    val data = store.cached(id) ?: run {
                        withContext(Dispatchers.Main) { onStatus("Reading game data from the ROM (first time only)…") }
                        store.scanAndCache(id, local.file.readBytes()) // GBA ROMs are at most 32 MB
                    }
                    SaveEditor(Gen3Save.parse(manager.saveFile.readBytes()), data)
                }
                OpenSave(editor, manager, id)
            }
    }
}
