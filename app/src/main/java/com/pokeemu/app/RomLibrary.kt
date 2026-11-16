package com.pokeemu.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pokeemu.saves.RomHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RomEntry(val uri: Uri, val fileName: String, val header: RomHeader)

/** Finds GBA ROMs in the user-chosen folder (Storage Access Framework tree). */
class RomLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_FOLDER, value?.toString()).apply()

    /** Scans [folder] (and subfolders up to [MAX_DEPTH]) for valid .gba files. */
    suspend fun scan(): List<RomEntry> = withContext(Dispatchers.IO) {
        val tree = folder?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext emptyList()
        val out = mutableListOf<RomEntry>()
        fun walk(dir: DocumentFile, depth: Int) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory && depth < MAX_DEPTH) {
                    walk(f, depth + 1)
                } else if (f.isFile && name.endsWith(".gba", ignoreCase = true)) {
                    readHeader(f.uri)?.let { out += RomEntry(f.uri, name, it) }
                }
            }
        }
        walk(tree, 0)
        out.sortedBy { it.fileName.lowercase() }
    }

    private fun readHeader(uri: Uri): RomHeader? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(RomHeader.HEADER_SIZE)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            RomHeader.parse(buf)
        }
    }.getOrNull()

    fun readRom(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open ROM")

    private companion object {
        const val KEY_FOLDER = "folder"
        const val MAX_DEPTH = 3
    }
}
