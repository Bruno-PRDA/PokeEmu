package com.pokeemu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pokeemu.saves.NdsHeader
import com.pokeemu.saves.Platform
import com.pokeemu.saves.RomHeader
import com.pokeemu.saves.romId
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A game in the library: a ROM stored inside the app. [uri] is a file:// URI to that copy. */
data class RomEntry(val uri: Uri, val fileName: String, val header: RomHeader, val added: Long = 0, val id: String = "") {
    val name get() = fileName.substringBeforeLast('.')
}

/**
 * The game library. Games are imported: the ROM is copied into the app's own
 * storage, so the user can delete the downloaded file afterwards. Each game
 * lives in library/<id>/ (the ROM, plus a small "meta" file with its original
 * name and id). Saves live apart, in games/<romId>/ (GameStorage), so removing
 * a game never touches them.
 */
class RomLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val gamesDir = File(context.filesDir, "library")
    /** Older versions copied played ROMs here, keyed by the source URI. */
    private val legacyCache = File(context.filesDir, "rom-cache")

    /** Folder picked by older versions (games were read from it in place); imported once, then cleared. */
    var legacyFolder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_FOLDER, value?.toString()).apply()

    sealed interface ImportResult {
        data class Added(val entry: RomEntry) : ImportResult
        data class AlreadyThere(val entry: RomEntry) : ImportResult
        data object NotARom : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    /** All imported games, oldest first. */
    suspend fun games(): List<RomEntry> = withContext(Dispatchers.IO) {
        moveOutOfSaveFolders()
        gamesDir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull(::entryIn).sortedBy { it.added }
    }

    /**
     * Copies a GBA or DS ROM into the app. The platform is detected from the
     * header, whatever the file is called. Importing the same ROM twice keeps one copy.
     */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val header = runCatching { context.contentResolver.openInputStream(uri)?.use(::detect) }.getOrNull()
            ?: return@withContext ImportResult.NotARom
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Game"
        gamesDir.mkdirs()
        val tmp = File(gamesDir, "import-${System.nanoTime()}.tmp")
        try {
            // A copy made by an older version when the game was played can be moved instead of copied again.
            val cached = File(legacyCache, "${key(uri)}.rom")
            val sourceSize = DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L
            if (!(cached.isFile && cached.length() == sourceSize && cached.renameTo(tmp))) {
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    ?: return@withContext ImportResult.Failed("Could not open the file")
            }
            File(legacyCache, "${key(uri)}.id").delete()
            val id = romId(tmp)
            val dir = File(gamesDir, id.take(16))
            entryIn(dir)?.let { tmp.delete(); return@withContext ImportResult.AlreadyThere(it) }
            dir.mkdirs()
            val rom = File(dir, ROM_FILE)
            check(tmp.renameTo(rom)) { "Could not store the game" }
            Properties().apply {
                setProperty("name", name)
                setProperty("id", id)
                setProperty("added", System.currentTimeMillis().toString())
            }.store(File(dir, META_FILE).outputStream().buffered(), null)
            ImportResult.Added(RomEntry(Uri.fromFile(rom), name, header, System.currentTimeMillis(), id))
        } catch (e: Exception) {
            tmp.delete()
            ImportResult.Failed(e.message ?: "Import failed")
        }
    }

    /** Imports every .gba/.nds file in a folder tree (and subfolders up to [MAX_DEPTH]). Returns how many were new. */
    suspend fun importFolder(tree: Uri, onFile: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext 0
        val files = mutableListOf<DocumentFile>()
        fun walk(dir: DocumentFile, depth: Int) {
            for (f in runCatching { dir.listFiles() }.getOrDefault(emptyArray())) {
                val name = f.name ?: continue
                if (f.isDirectory && depth < MAX_DEPTH) walk(f, depth + 1)
                else if (f.isFile && Platform.forFileName(name) != null) files += f
            }
        }
        walk(root, 0)
        files.count { f ->
            onFile(f.name.orEmpty())
            import(f.uri) is ImportResult.Added
        }
    }

    /** Deletes the game's ROM from the app. Its saves and save states are kept. */
    suspend fun remove(entry: RomEntry) = withContext(Dispatchers.IO) {
        val rom = entry.uri.path?.let(::File) ?: return@withContext
        val dir = rom.parentFile ?: return@withContext
        if (dir.parentFile != gamesDir) return@withContext
        // Only what import put there; never a whole folder.
        File(dir, ROM_FILE).delete()
        File(dir, META_FILE).delete()
        dir.delete() // only succeeds if now empty
        iconFile(entry.uri).delete()
    }

    /**
     * A build from 2026-11-17 stored ROMs in games/<id>/, the folder that also
     * holds that game's saves. Move them (a rename, no copying) to library/.
     */
    private fun moveOutOfSaveFolders() {
        val old = File(context.filesDir, "games")
        for (dir in old.listFiles { f -> f.isDirectory }.orEmpty()) {
            val rom = File(dir, ROM_FILE)
            val meta = File(dir, META_FILE)
            if (!rom.isFile || !meta.isFile) continue
            val target = File(gamesDir, dir.name).apply { mkdirs() }
            if (rom.renameTo(File(target, ROM_FILE))) meta.renameTo(File(target, META_FILE))
        }
    }

    private fun entryIn(dir: File): RomEntry? {
        val rom = File(dir, ROM_FILE)
        val meta = File(dir, META_FILE)
        if (!rom.isFile || !meta.isFile) return null
        val props = runCatching { Properties().apply { meta.inputStream().use(::load) } }.getOrNull() ?: return null
        val header = runCatching { rom.inputStream().use(::detect) }.getOrNull() ?: return null
        return RomEntry(
            Uri.fromFile(rom), props.getProperty("name", "Game"), header,
            props.getProperty("added")?.toLongOrNull() ?: 0, props.getProperty("id", ""),
        )
    }

    /** Reads the header and tells DS from GBA by content (DS header CRC, GBA fixed byte). */
    private fun detect(input: InputStream): RomHeader? {
        val buf = ByteArray(NdsHeader.HEADER_SIZE)
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return NdsHeader.parse(buf) ?: RomHeader.parse(buf)
    }

    /**
     * The game's icon: DS games carry a 32x32 icon in their banner. Cached as a
     * PNG. Null for GBA games (they have none).
     */
    suspend fun icon(entry: RomEntry): Bitmap? = withContext(Dispatchers.IO) {
        if (entry.header.platform != Platform.NDS) return@withContext null
        val cached = iconFile(entry.uri)
        if (cached.isFile) return@withContext BitmapFactory.decodeFile(cached.path)
        val bitmap = runCatching { readDsIcon(File(entry.uri.path!!)) }.getOrNull() ?: return@withContext null
        cached.parentFile?.mkdirs()
        runCatching { cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap
    }

    private fun iconFile(uri: Uri) = File(context.cacheDir, "icons/${key(uri)}.png")

    private fun readDsIcon(rom: File): Bitmap? = RandomAccessFile(rom, "r").use { f ->
        fun readAt(pos: Long, len: Int) = ByteArray(len).also { f.seek(pos); f.readFully(it) }
        val bannerAt = u32(readAt(0, 0x6C), 0x68)
        if (bannerAt == 0L || bannerAt + 0x240 > f.length()) return null
        DsIcon.decode(readAt(bannerAt, 0x240))
    }

    /**
     * The game's picture for the main menu: the cover the player chose, else
     * one drawn from the ROM (its mascot Pokémon, see [DefaultCover]); null if
     * neither can be had. Slow the first time for a game (reads the ROM).
     */
    fun coverFile(entry: RomEntry): File? {
        if (entry.id.isEmpty()) return null
        val storage = com.pokeemu.saves.GameStorage(context.filesDir)
        return storage.coverFile(entry.id).takeIf { it.isFile } ?: DefaultCover.get(context, entry)
    }

    fun hasCustomCover(entry: RomEntry) =
        entry.id.isNotEmpty() && com.pokeemu.saves.GameStorage(context.filesDir).coverFile(entry.id).isFile

    /** Saves [bitmap] (scaled down to at most 720 px) as the game's cover. */
    fun setCover(entry: RomEntry, bitmap: Bitmap) {
        val scale = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val file = com.pokeemu.saves.GameStorage(context.filesDir).coverFile(entry.id)
        val tmp = File(file.parentFile, "cover.tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun removeCover(entry: RomEntry) {
        if (entry.id.isNotEmpty()) com.pokeemu.saves.GameStorage(context.filesDir).coverFile(entry.id).delete()
    }

    /** A ROM ready to play: a local file (mmap-able by native code) and its id. */
    data class LocalRom(val file: File, val id: String)

    /** The stored ROM for a library entry's [uri], with its id. */
    fun localCopy(uri: Uri): LocalRom {
        val rom = File(requireNotNull(uri.path) { "Not a library game" })
        val meta = File(rom.parentFile, META_FILE)
        val id = runCatching { Properties().apply { meta.inputStream().use(::load) }.getProperty("id") }.getOrNull()
            ?: romId(rom)
        return LocalRom(rom, id)
    }

    private fun key(uri: Uri) = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        const val KEY_FOLDER = "folder"
        const val MAX_DEPTH = 3
        const val ROM_FILE = "rom"
        const val META_FILE = "meta"

        fun u32(b: ByteArray, at: Int) =
            (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
    }
}

/** Decodes the DS banner icon: 4x4 tiles of 8x8 4-bit pixels at 0x20, 16 BGR555 colours at 0x220 (colour 0 clear). */
object DsIcon {
    fun decode(banner: ByteArray): Bitmap? {
        if (banner.size < 0x240) return null
        val palette = IntArray(16) { i ->
            val c = (banner[0x220 + i * 2].toInt() and 0xFF) or ((banner[0x221 + i * 2].toInt() and 0xFF) shl 8)
            fun ch(v: Int) = (v and 31) * 255 / 31
            if (i == 0) 0 else (0xFF shl 24) or (ch(c) shl 16) or (ch(c shr 5) shl 8) or ch(c shr 10)
        }
        if (palette.drop(1).all { it == palette[1] }) return null // blank banner
        val pixels = IntArray(32 * 32)
        for (tile in 0 until 16) for (y in 0 until 8) for (x in 0 until 8) {
            val byte = banner[0x20 + tile * 32 + y * 4 + x / 2].toInt() and 0xFF
            val index = if (x % 2 == 0) byte and 0xF else byte shr 4
            pixels[((tile / 4) * 8 + y) * 32 + (tile % 4) * 8 + x] = palette[index]
        }
        return Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888)
    }
}
