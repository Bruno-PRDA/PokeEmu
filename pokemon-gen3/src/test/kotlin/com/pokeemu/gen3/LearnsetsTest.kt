package com.pokeemu.gen3

import com.pokeemu.core.Learnsets
import com.pokeemu.core.MoveSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnsetsTest {
    // Species 1 -> 2 -> 3 (a starter line), species 4 alone.
    private val l = Learnsets(
        levelUp = listOf(emptyList(), listOf(1 to 10, 7 to 11), listOf(0 to 20, 16 to 21), listOf(36 to 30), listOf(1 to 40)),
        tmMoves = listOf(100, 101, 102), hmCount = 1,
        tmCompat = listOf(BitSet(), BitSet(), BitSet(), BitSet().apply { set(0); set(2) }, BitSet().apply { set(1) }),
        eggMoves = mapOf(1 to listOf(50, 51)),
        evolutions = listOf(emptyList(), listOf(2), listOf(3), emptyList(), emptyList()),
    )

    @Test fun evolvedFormsKeepEarlierMovesAndTheFirstFormsEggMoves() {
        val m = l.learnable(3)
        assertEquals(listOf(3, 2, 1), l.lineage(3))
        assertEquals(MoveSource(MoveSource.Kind.LEVEL, 36), m[30])
        assertEquals(MoveSource(MoveSource.Kind.LEVEL, 16, via = 2), m[21])
        assertEquals(MoveSource(MoveSource.Kind.LEVEL, 7, via = 1), m[11])
        assertEquals(MoveSource(MoveSource.Kind.TM, 1), m[100])
        assertEquals(MoveSource(MoveSource.Kind.HM, 1), m[102]) // last entry of the list is the HM
        assertEquals(MoveSource(MoveSource.Kind.EGG, 0, via = 1), m[50])
        assertFalse(101 in m)
        assertFalse(40 in m)
    }

    @Test fun unrelatedSpeciesDontShareMoves() {
        val m = l.learnable(4)
        assertEquals(setOf(40, 101), m.keys)
    }

    @Test fun survivesTheRomDataCache() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(l::write)
        val back = Learnsets.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))
        assertEquals(l, back)
        assertEquals(l.learnable(3), back.learnable(3))
        assertTrue(Learnsets.Part.TM in back.has)
    }
}
