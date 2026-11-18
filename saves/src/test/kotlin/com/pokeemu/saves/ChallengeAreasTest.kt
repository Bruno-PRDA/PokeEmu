package com.pokeemu.saves

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChallengeAreasTest {
    @Test fun areasAreKeptPerLocationAndCleared() {
        val dir = Files.createTempDirectory("areas").toFile()
        val store = ChallengeStore(GameStorage(dir), "rom", "main")
        assertNull(store.area(16))
        store.putArea(ChallengeStore.Area(16, "ROUTE 101", 7, caught = false, text = "Wild Zigzagoon Lv 2"))
        store.putArea(ChallengeStore.Area(17, "ROUTE|102", 8, caught = true, text = "Caught Poochyena"))
        store.putArea(ChallengeStore.Area(16, "ROUTE 101", 7, caught = true, text = "Caught Zigzagoon"))
        assertEquals(listOf(17, 16), store.areas().map { it.location })
        assertEquals("Caught Zigzagoon", store.area(16)!!.text)
        assertEquals("ROUTE 102", store.area(17)!!.place)
        store.clearLog()
        assertEquals(emptyList(), store.areas())
        dir.deleteRecursively()
    }
}
