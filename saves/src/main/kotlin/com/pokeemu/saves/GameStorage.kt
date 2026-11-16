package com.pokeemu.saves

import java.io.File

/**
 * On-disk layout for everything the app stores per game:
 *
 * ```
 * <root>/games/<romId>/game.sav                     battery save of the active slot (the emulator reads/writes this)
 * <root>/games/<romId>/active-slot                  name of the active save slot
 * <root>/games/<romId>/slots/<slot>.sav             parked saves of inactive slots
 * <root>/games/<romId>/states/<slot>/<n>.state|png  save states, per save slot (n = 0 is the auto state)
 * <root>/games/<romId>/backups/<millis>-<reason>.sav  backups (+ .pin marker to keep forever)
 * ```
 */
class GameStorage(private val root: File) {

    fun gameDir(romId: String): File = File(root, "games/$romId").apply { mkdirs() }

    fun saveFile(romId: String): File = File(gameDir(romId), "game.sav")

    fun slotsDir(romId: String): File = File(gameDir(romId), "slots").apply { mkdirs() }

    fun backupsDir(romId: String): File = File(gameDir(romId), "backups").apply { mkdirs() }

    private fun activeSlotFile(romId: String) = File(gameDir(romId), "active-slot")

    fun activeSlot(romId: String): String =
        activeSlotFile(romId).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SLOT

    fun setActiveSlot(romId: String, slot: String) = writeAtomically(activeSlotFile(romId), slot.toByteArray())

    fun stateFile(romId: String, slot: Int): File = File(statesDir(romId), "$slot.state")

    fun thumbnailFile(romId: String, slot: Int): File = File(statesDir(romId), "$slot.png")

    /** Slots that currently hold a state, with their last-modified time. */
    fun listStates(romId: String): List<StateSlot> =
        (0..MAX_SLOT).mapNotNull { slot ->
            val f = stateFile(romId, slot)
            if (f.isFile) StateSlot(slot, f.lastModified(), thumbnailFile(romId, slot).takeIf { it.isFile }) else null
        }

    /** Writes a state atomically (temp file + rename). */
    fun writeState(romId: String, slot: Int, state: ByteArray) {
        require(slot in 0..MAX_SLOT) { "slot $slot out of range" }
        writeAtomically(stateFile(romId, slot), state)
    }

    fun readState(romId: String, slot: Int): ByteArray? = stateFile(romId, slot).takeIf { it.isFile }?.readBytes()

    /**
     * Deletes the auto state of the active save slot. Needed whenever the save
     * file changes outside the game: the auto state holds the *old* save in the
     * game's RAM and would write it back on the next in-game save.
     */
    fun invalidateAutoState(romId: String) {
        stateFile(romId, AUTO_SLOT).delete()
        thumbnailFile(romId, AUTO_SLOT).delete()
    }

    /** States of the active save slot. */
    private fun statesDir(romId: String) =
        File(gameDir(romId), "states/${activeSlot(romId)}").apply { mkdirs() }

    data class StateSlot(val slot: Int, val modifiedAt: Long, val thumbnail: File?)

    companion object {
        /** Slot 0 is the automatic "on exit" state; 1..10 are manual. */
        const val AUTO_SLOT = 0
        const val MAX_SLOT = 10
        const val DEFAULT_SLOT = "Main"

        fun writeAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "could not write $target" }
            }
        }
    }
}
