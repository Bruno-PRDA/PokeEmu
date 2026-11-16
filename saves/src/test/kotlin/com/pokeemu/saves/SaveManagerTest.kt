package com.pokeemu.saves

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveManagerTest {
    private val root = Files.createTempDirectory("savemgr").toFile()
    private val storage = GameStorage(root)
    private var now = 1_000_000L
    private val mgr = SaveManager(storage, "rom1") { now }

    private fun sav(fill: Int) = ByteArray(128 * 1024) { fill.toByte() }
    private fun writeSave(fill: Int) = mgr.saveFile.writeBytes(sav(fill))
    private fun currentFill() = mgr.saveFile.readBytes()[0].toInt()

    @Test fun backupSkipsWhenNoSaveOrUnchanged() {
        assertNull(mgr.backup("manual"))
        writeSave(1)
        assertNotNull(mgr.backup("manual"))
        now += 10
        assertNull(mgr.backup("manual"), "identical to newest backup")
        writeSave(2)
        now += 10
        assertNotNull(mgr.backup("manual"))
        assertEquals(2, mgr.backups().size)
        assertEquals("manual", mgr.backups().first().reason)
    }

    @Test fun dailyBackupOnlyOncePerDay() {
        writeSave(1)
        assertNotNull(mgr.dailyBackupIfDue())
        writeSave(2)
        now += SaveManager.DAY_MS / 2
        assertNull(mgr.dailyBackupIfDue())
        now += SaveManager.DAY_MS
        assertNotNull(mgr.dailyBackupIfDue())
    }

    @Test fun pruneKeepsPinnedAndNewest20() {
        writeSave(0)
        val first = mgr.backup("manual")!!
        mgr.setPinned(first, true)
        for (i in 1..25) {
            writeSave(i); now += 1; mgr.backup("manual")
        }
        val all = mgr.backups()
        assertEquals(21, all.size)
        assertTrue(all.any { it.pinned && it.createdAt == first.createdAt }, "pinned backup survives")
        assertEquals(25, all.first().file.readBytes()[0].toInt(), "newest kept")
    }

    @Test fun restoreBacksUpCurrentAndDropsAutoState() {
        writeSave(1)
        val b = mgr.backup("manual")!!
        writeSave(2)
        storage.writeState("rom1", GameStorage.AUTO_SLOT, byteArrayOf(9))
        now += 5
        mgr.restore(b)
        assertEquals(1, currentFill())
        assertNull(storage.readState("rom1", GameStorage.AUTO_SLOT), "stale auto state removed")
        assertEquals(SaveManager.REASON_BEFORE_RESTORE, mgr.backups().first().reason)
        assertEquals(2, mgr.backups().first().file.readBytes()[0].toInt())
    }

    @Test fun importValidatesSize() {
        assertFailsWith<IllegalArgumentException> { mgr.import(ByteArray(1000)) }
        mgr.import(sav(7))
        assertEquals(7, currentFill())
    }

    @Test fun slotsSwapSavesAndStates() {
        writeSave(1)
        storage.writeState("rom1", 1, byteArrayOf(1))
        assertEquals(listOf("Main"), mgr.slots())

        mgr.createSlot("Nuzlocke", copyCurrent = false)
        mgr.switchTo("Nuzlocke")
        assertEquals("Nuzlocke", mgr.activeSlot)
        assertFalse(mgr.hasSave, "empty slot = new game")
        assertNull(storage.readState("rom1", 1), "states are per slot")

        writeSave(2)
        mgr.switchTo("Main")
        assertEquals(1, currentFill())
        assertContentEquals(byteArrayOf(1), storage.readState("rom1", 1))

        mgr.switchTo("Nuzlocke")
        assertEquals(2, currentFill())
        assertEquals(listOf("Main", "Nuzlocke"), mgr.slots())
    }

    @Test fun copySlotRenameAndDelete() {
        writeSave(3)
        mgr.createSlot("Copy", copyCurrent = true)
        assertFailsWith<IllegalArgumentException> { mgr.createSlot("copy ", copyCurrent = false) }
        assertEquals(3, storage.slotsDir("rom1").resolve("Copy.sav").readBytes()[0].toInt(), "existing slot untouched")
        mgr.renameSlot("Copy", "Backup run")
        mgr.switchTo("Backup run")
        assertEquals(3, currentFill())
        mgr.renameSlot("Backup run", "Run 2") // active slot rename
        assertEquals("Run 2", mgr.activeSlot)
        assertFailsWith<IllegalArgumentException> { mgr.deleteSlot("Run 2") }
        mgr.deleteSlot("Main")
        assertEquals(listOf("Run 2"), mgr.slots())
        assertTrue(mgr.backups().any { it.reason.startsWith("deleted-slot") })
    }

    @Test fun rejectsBadSlotNames() {
        assertFailsWith<IllegalArgumentException> { mgr.createSlot("a/b", false) }
        assertFailsWith<IllegalArgumentException> { mgr.createSlot("  ", false) }
    }
}
