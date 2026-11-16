package com.pokeemu.saves

import java.io.File

/**
 * Backups, save slots and import for one game's battery save.
 *
 * Every operation that replaces the save takes a backup first and deletes the
 * active slot's auto state (see [GameStorage.invalidateAutoState]). Callers
 * must make sure the game is not running while these run.
 */
class SaveManager(
    private val storage: GameStorage,
    private val romId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Backup(val file: File, val createdAt: Long, val reason: String, val pinned: Boolean, val size: Long)

    val saveFile: File get() = storage.saveFile(romId)
    val hasSave: Boolean get() = saveFile.isFile && saveFile.length() > 0

    // ---- backups ------------------------------------------------------------------

    /** Newest first. */
    fun backups(): List<Backup> =
        storage.backupsDir(romId).listFiles { f -> f.extension == "sav" }.orEmpty()
            .mapNotNull { f ->
                val m = BACKUP_NAME.matchEntire(f.nameWithoutExtension) ?: return@mapNotNull null
                Backup(f, m.groupValues[1].toLong(), m.groupValues[2], pinMarker(f).isFile, f.length())
            }
            .sortedByDescending { it.createdAt }

    /**
     * Copies the current save into a backup. Skipped (returns null) when there is
     * no save, or when it is identical to the newest backup.
     */
    fun backup(reason: String): Backup? {
        if (!hasSave) return null
        val bytes = saveFile.readBytes()
        val newest = backups().firstOrNull()
        if (newest != null && newest.file.readBytes().contentEquals(bytes)) return null
        val safeReason = reason.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "manual" }
        var time = clock()
        while (newest != null && time <= newest.createdAt) time = newest.createdAt + 1 // keep names unique & ordered
        val file = File(storage.backupsDir(romId), "$time-$safeReason.sav")
        GameStorage.writeAtomically(file, bytes)
        prune()
        return backups().first { it.file == file }
    }

    /** Takes a "daily" backup if the newest backup is more than a day old. */
    fun dailyBackupIfDue(): Backup? {
        val newest = backups().firstOrNull()
        if (newest != null && clock() - newest.createdAt < DAY_MS) return null
        return backup(REASON_DAILY)
    }

    fun setPinned(backup: Backup, pinned: Boolean) {
        val marker = pinMarker(backup.file)
        if (pinned) marker.writeText("") else marker.delete()
    }

    fun delete(backup: Backup) {
        backup.file.delete()
        pinMarker(backup.file).delete()
    }

    /** Replaces the save with [backup] (backing up the current save first). */
    fun restore(backup: Backup) = replaceSave(backup.file.readBytes(), REASON_BEFORE_RESTORE)

    /** Keeps every pinned backup plus the newest [MAX_UNPINNED] unpinned ones. */
    private fun prune() {
        backups().filterNot { it.pinned }.drop(MAX_UNPINNED).forEach(::delete)
    }

    private fun pinMarker(f: File) = File(f.parentFile, f.nameWithoutExtension + ".pin")

    // ---- import -------------------------------------------------------------------------

    /** Replaces the save with imported bytes. Throws if they don't look like a GBA save. */
    fun import(bytes: ByteArray) {
        require(isPlausibleSaveSize(bytes.size)) {
            "Not a save file (${bytes.size} bytes; GBA/DS saves are a power of two from 512 B to 32 MB)"
        }
        replaceSave(bytes, REASON_BEFORE_IMPORT)
    }

    /**
     * Writes an edited save: backup → temp file → [verify] the temp file's
     * bytes (throw to abort) → replace. The original is untouched on failure.
     */
    fun applyEdit(bytes: ByteArray, verify: (ByteArray) -> Unit) {
        backup(REASON_BEFORE_EDIT)
        val tmp = File(saveFile.parentFile, saveFile.name + ".edit")
        try {
            tmp.writeBytes(bytes)
            verify(tmp.readBytes())
            GameStorage.writeAtomically(saveFile, tmp.readBytes())
        } finally {
            tmp.delete()
        }
        storage.invalidateAutoState(romId)
    }

    private fun replaceSave(bytes: ByteArray, reason: String) {
        backup(reason)
        GameStorage.writeAtomically(saveFile, bytes)
        storage.invalidateAutoState(romId)
    }

    // ---- save slots ---------------------------------------------------------------------

    val activeSlot: String get() = storage.activeSlot(romId)

    /** All slot names, active one included, sorted. */
    fun slots(): List<String> =
        (storage.slotsDir(romId).listFiles { f -> f.extension == "sav" }.orEmpty().map { it.nameWithoutExtension } + activeSlot)
            .distinct().sortedBy { it.lowercase() }

    /**
     * Creates slot [name]. With [copyCurrent] it starts as a copy of the current
     * save, otherwise empty (the game will start a new adventure).
     */
    fun createSlot(name: String, copyCurrent: Boolean) {
        val clean = validateSlotName(name)
        requireUnique(clean)
        val file = parkedFile(clean)
        if (copyCurrent && hasSave) saveFile.copyTo(file) else GameStorage.writeAtomically(file, ByteArray(0))
    }

    /** Parks the active save in its slot and makes [name] active. */
    fun switchTo(name: String) {
        if (name == activeSlot) return
        val target = parkedFile(name)
        require(target.isFile) { "No slot named \"$name\"" }
        backup(REASON_BEFORE_SWITCH)
        // Park current save (may be empty if the slot never saved).
        GameStorage.writeAtomically(parkedFile(activeSlot), if (hasSave) saveFile.readBytes() else ByteArray(0))
        val incoming = target.readBytes()
        if (incoming.isEmpty()) saveFile.delete() else GameStorage.writeAtomically(saveFile, incoming)
        target.delete()
        storage.setActiveSlot(romId, name)
    }

    fun renameSlot(from: String, to: String) {
        val clean = validateSlotName(to)
        if (clean == from) return
        requireUnique(clean, ignoring = from)
        if (from == activeSlot) {
            storage.setActiveSlot(romId, clean)
        } else {
            check(parkedFile(from).renameTo(parkedFile(clean))) { "Could not rename slot" }
        }
        // States follow the slot.
        val statesRoot = File(storage.gameDir(romId), "states")
        File(statesRoot, from).takeIf { it.isDirectory }?.renameTo(File(statesRoot, clean))
    }

    /** Deletes an inactive slot (a backup of it is kept). */
    fun deleteSlot(name: String) {
        require(name != activeSlot) { "Switch to another slot before deleting this one" }
        val file = parkedFile(name)
        if (file.isFile && file.length() > 0) {
            GameStorage.writeAtomically(
                File(storage.backupsDir(romId), "${clock()}-deleted-slot-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.sav"),
                file.readBytes(),
            )
        }
        file.delete()
        File(storage.gameDir(romId), "states/$name").deleteRecursively()
    }

    /** Names are unique ignoring case (some filesystems are case-insensitive). */
    private fun requireUnique(name: String, ignoring: String? = null) {
        require(slots().none { it.equals(name, ignoreCase = true) && it != ignoring }) {
            "A slot named \"$name\" already exists"
        }
    }

    private fun parkedFile(name: String) = File(storage.slotsDir(romId), "$name.sav")

    private fun validateSlotName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 32) { "Slot names must be 1–32 characters" }
        require(clean.none { it in "/\\:*?\"<>|" } && !clean.startsWith(".")) { "Slot names can't contain / \\ : * ? \" < > |" }
        return clean
    }

    companion object {
        const val MAX_UNPINNED = 20
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val REASON_DAILY = "daily"
        const val REASON_MANUAL = "manual"
        const val REASON_BEFORE_EDIT = "before-edit"
        const val REASON_BEFORE_RESTORE = "before-restore"
        const val REASON_BEFORE_IMPORT = "before-import"
        const val REASON_BEFORE_SWITCH = "before-slot-switch"

        /** GBA saves are 512 B..128 KB; DS saves 512 B..8 MB (some flash carts up to 32 MB). */
        fun isPlausibleSaveSize(size: Int): Boolean = size in 512..(32 shl 20) && size and (size - 1) == 0
        private val BACKUP_NAME = Regex("""(\d+)-([a-z0-9-]+)""")
    }
}
