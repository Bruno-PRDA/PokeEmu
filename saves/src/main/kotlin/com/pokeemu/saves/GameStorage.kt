package com.pokeemu.saves

import java.io.File

/**
 * On-disk layout for everything the app stores per game:
 *
 * ```
 * <root>/games/<romId>/game.sav         battery save (written by the emulator)
 * <root>/games/<romId>/states/<n>.state save state, slot n (0 = auto)
 * <root>/games/<romId>/states/<n>.png   thumbnail for slot n
 * ```
 */
class GameStorage(private val root: File) {

    fun gameDir(romId: String): File = File(root, "games/$romId").apply { mkdirs() }

    fun saveFile(romId: String): File = File(gameDir(romId), "game.sav")

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

    private fun statesDir(romId: String) = File(gameDir(romId), "states").apply { mkdirs() }

    data class StateSlot(val slot: Int, val modifiedAt: Long, val thumbnail: File?)

    companion object {
        /** Slot 0 is the automatic "on exit" state; 1..10 are manual. */
        const val AUTO_SLOT = 0
        const val MAX_SLOT = 10

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
