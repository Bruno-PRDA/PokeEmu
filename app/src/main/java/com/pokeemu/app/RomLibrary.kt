package com.pokeemu.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pokeemu.saves.NdsHeader
import com.pokeemu.saves.Platform
import com.pokeemu.saves.RomHeader
import com.pokeemu.saves.romId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RomEntry(val uri: Uri, val fileName: String, val header: RomHeader)

/** Finds GBA ROMs in the user-chosen folder (Storage Access Framework tree). */
class RomLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_FOLDER, value?.toString()).apply()

    /** Scans [folder] (and subfolders up to [MAX_DEPTH]) for valid .gba and .nds files. */
    suspend fun scan(): List<RomEntry> = withContext(Dispatchers.IO) {
        val tree = folder?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext emptyList()
        val out = mutableListOf<RomEntry>()
        fun walk(dir: DocumentFile, depth: Int) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory && depth < MAX_DEPTH) {
                    walk(f, depth + 1)
                } else if (f.isFile) {
                    val platform = Platform.forFileName(name) ?: continue
                    readHeader(f.uri, platform)?.let { out += RomEntry(f.uri, name, it) }
                }
            }
        }
        walk(tree, 0)
        out.sortedBy { it.fileName.lowercase() }
    }

    private fun readHeader(uri: Uri, platform: Platform): RomHeader? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(if (platform == Platform.NDS) NdsHeader.HEADER_SIZE else RomHeader.HEADER_SIZE)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            if (platform == Platform.NDS) NdsHeader.parse(buf) else RomHeader.parse(buf)
        }
    }.getOrNull()

    /** A ROM ready to play: a private local copy (mmap-able by native code) and its id. */
    data class LocalRom(val file: File, val id: String)

    /**
     * Copies the ROM out of the user's folder into app storage (once; reused while
     * the source size is unchanged) and returns it with its id. Nothing is loaded
     * into memory, so 256 MB+ DS ROMs are fine.
     */
    fun localCopy(uri: Uri): LocalRom {
        val dir = File(context.filesDir, "rom-cache").apply { mkdirs() }
        val key = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val copy = File(dir, "$key.rom")
        val idFile = File(dir, "$key.id")
        val sourceSize = DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L
        if (!(copy.isFile && copy.length() == sourceSize && idFile.isFile)) {
            val tmp = File(dir, "$key.tmp")
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                ?: error("Cannot open ROM")
            check(tmp.renameTo(copy) || (copy.delete() && tmp.renameTo(copy))) { "Could not store ROM copy" }
            idFile.writeText(romId(copy))
        }
        return LocalRom(copy, idFile.readText().trim())
    }

    private companion object {
        const val KEY_FOLDER = "folder"
        const val MAX_DEPTH = 3
    }
}
