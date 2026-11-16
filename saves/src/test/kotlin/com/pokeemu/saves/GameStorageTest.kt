package com.pokeemu.saves

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameStorageTest {
    private val root = Files.createTempDirectory("storage").toFile()
    private val storage = GameStorage(root)

    @Test fun statesRoundTripAndAreListed() {
        storage.writeState("abc", 3, byteArrayOf(1, 2, 3))
        storage.writeState("abc", 3, byteArrayOf(4, 5))  // overwrite
        assertContentEquals(byteArrayOf(4, 5), storage.readState("abc", 3))
        assertEquals(listOf(3), storage.listStates("abc").map { it.slot })
        assertNull(storage.readState("abc", 4))
    }

    @Test fun gamesAreIsolated() {
        storage.writeState("one", 1, byteArrayOf(1))
        assertTrue(storage.listStates("two").isEmpty())
        assertTrue(storage.saveFile("one") != storage.saveFile("two"))
    }

    @Test fun rejectsInvalidSlots() {
        assertFailsWith<IllegalArgumentException> { storage.writeState("abc", 11, byteArrayOf()) }
    }
}
