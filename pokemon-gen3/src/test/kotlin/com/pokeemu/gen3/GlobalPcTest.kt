package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.GlobalPc
import com.pokeemu.core.Slot
import com.pokeemu.core.Transfer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlobalPcTest {
    private val base = BaseStats(listOf(35, 55, 30, 90, 50, 40), 13, 13, 127, 0, 9, 0)

    /** A "vanilla" game: PIKACHU is #25, moves MOVE0..MOVE99, items IT0..IT9. */
    private val vanilla = RomData(
        speciesNames = List(30) { "MON$it" }.toMutableList().apply { this[25] = "PIKACHU" },
        moveNames = List(100) { "MOVE$it" }, abilityNames = List(10) { "AB$it" },
        itemNames = List(10) { "IT$it" },
        baseStats = List(30) { if (it > 0) base else null }, movePp = List(100) { 10 }, report = emptyList(),
    )

    /** A hack: species reordered (Pikachu is #7, spelled differently), moves reversed, no item 3. */
    private val hack = RomData(
        speciesNames = List(30) { "HACKMON$it" }.toMutableList().apply { this[7] = "Pikachu" },
        moveNames = List(100) { if (it == 0) "-" else "MOVE${100 - it}" },
        abilityNames = List(10) { "AB$it" },
        itemNames = List(10) { if (it == 3) "SOMETHING" else "IT${9 - it}" },
        baseStats = List(30) { if (it > 0) base else null }, movePp = List(100) { 10 }, report = emptyList(),
    )

    private fun pikachu(party: Boolean) = Pk3.create(0x1234, (54321L shl 16) or 12345L, party).apply {
        species = 25
        experience = GameRules.expForLevel(0, 30)
        nickname = "SPARKY"
        heldItem = 2
        setMove(0, 5); setMove(1, 3)
        if (party) setPartyStats(30, GameRules.stats(base, 30, ivs, evs, nature))
    }

    private fun editor(rom: RomData, boxed: Pk3? = null): SaveEditor {
        val save = Gen3Save.parse(Gen3SaveFixtures.emerald(listOf(pikachu(party = true))))
        boxed?.let { save.setBoxSlot(0, 0, it) }
        return SaveEditor(Gen3Save.parse(save.toBytes()), rom)
    }

    private fun pc() = GlobalPc(Files.createTempDirectory("pc").toFile())

    @Test fun sameGameRoundTripKeepsTheExactPokemon() {
        val src = editor(vanilla, boxed = pikachu(party = false))
        val pc = pc()
        val original = src[Slot.Box(0, 0)]!!
        val stored = pc.deposit(src, original, "Emerald", "rom-a")
        assertEquals("PIKACHU", stored.speciesName)
        assertEquals(listOf("MOVE5", "MOVE3"), stored.moveNames)
        assertEquals("IT2", stored.itemName)

        val result = Transfer.adapt(stored, editor(vanilla), "rom-a")
        assertContentEquals(src.exportBytes(original), (result.mon as Pk3).toBoxBytes())
        assertTrue(result.notes.isEmpty())
    }

    @Test fun anotherGameMatchesSpeciesMovesAndItemsByName() {
        val src = editor(vanilla, boxed = pikachu(party = false))
        val stored = pc().deposit(src, src[Slot.Box(0, 0)]!!, "Emerald", "rom-a")
        val target = editor(hack)
        val mon = Transfer.adapt(stored, target, "rom-b").mon
        assertEquals(7, mon.species)
        assertEquals(listOf(95, 97, 0, 0), mon.moves) // MOVE5 / MOVE3 in the hack's order
        assertEquals(10, mon.pp(0))
        assertEquals(7, mon.heldItem) // IT2
        assertEquals("SPARKY", mon.nickname)
        assertEquals(30, target.level(mon), "same species, same level")

        // It goes into the hack's PC and survives saving.
        target.insert(Slot.Box(3, 4), mon)
        val reread = Gen3Save.parse(target.toBytes())
        assertEquals(7, SaveEditor(reread, hack).box(3)[4]?.species)
    }

    @Test fun missingSpeciesIsRefusedAndMissingItemsAreDropped() {
        val src = editor(vanilla, boxed = pikachu(party = false))
        val stored = pc().deposit(src, src[Slot.Box(0, 0)]!!, "Emerald", "rom-a")
        val noPikachu = hack.copy(speciesNames = List(30) { "HACKMON$it" })
        val error = assertFailsWith<IllegalArgumentException> { Transfer.adapt(stored, editor(noPikachu), "rom-b") }
        assertTrue("PIKACHU" in error.message!!)

        val noItem = hack.copy(itemNames = List(10) { "OTHER$it" })
        val result = Transfer.adapt(stored, editor(noItem), "rom-b")
        assertEquals(0, result.mon.heldItem)
        assertTrue(result.notes.single().contains("IT2"))
    }

    @Test fun damagedRecordsStayPut() {
        val broken = pikachu(party = false)
        val bytes = broken.toBytes().also { it[0x30] = (it[0x30] + 1).toByte() } // data no longer matches its checksum
        val mon = Pk3(bytes) // as read from a save slot; writing a save would recompute the checksum
        assertTrue(!mon.checksumValid)
        val src = editor(vanilla)
        val error = assertFailsWith<IllegalArgumentException> { pc().deposit(src, mon, "Emerald", "rom-a") }
        assertTrue("damaged" in error.message!!)
    }

        @Test fun dsPokemonCannotGoIntoAGbaGame() {
        val src = editor(vanilla, boxed = pikachu(party = false))
        val stored = pc().deposit(src, src[Slot.Box(0, 0)]!!, "Emerald", "rom-a").copy(format = "pk4")
        assertFailsWith<IllegalArgumentException> { Transfer.adapt(stored, editor(vanilla), "rom-a") }
    }

    @Test fun storedPokemonSurviveRestartsAndCanBeRemoved() {
        val dir = Files.createTempDirectory("pc").toFile()
        val src = editor(vanilla, boxed = pikachu(party = false))
        val stored = GlobalPc(dir).deposit(src, src[Slot.Box(0, 0)]!!, "Emerald", "rom-a")
        val again = GlobalPc(dir).list().single()
        assertEquals(stored.id, again.id)
        assertEquals("SPARKY", again.nickname)
        assertEquals(30, again.level)
        assertContentEquals(stored.bytes, again.bytes)
        GlobalPc(dir).remove(again)
        assertTrue(GlobalPc(dir).list().isEmpty())
    }

    /**
     * Optional, with real data: POKEEMU_TEST_TRANSFER=<from.gba>;<from.sav>;<to.gba>[;<to.gba>…]
     * moves every Pokémon in the save into each target ROM and prints what it became.
     */
    @Test fun realTransferIfProvided() {
        val parts = System.getenv("POKEEMU_TEST_TRANSFER")?.split(';') ?: return
        val fromRom = RomScanner(java.io.File(parts[0]).readBytes()).scan()
        val source = SaveEditor(Gen3Save.parse(java.io.File(parts[1]).readBytes()), fromRom)
        val mons = (source.partyMembers.map { it } +
            (0 until source.boxCount).flatMap { b -> source.box(b).filterNotNull() }).filter { it.checksumValid }
        val pc = pc()
        val stored = mons.map { pc.deposit(source, it, "source", "from") }
        for (target in parts.drop(2)) {
            val toRom = RomScanner(java.io.File(target).readBytes()).scan()
            println("== into ${java.io.File(target).name}")
            for (s in stored) {
                val editor = editor(toRom)
                try {
                    val r = Transfer.adapt(s, editor, "to")
                    editor.insert(Slot.Box(0, 0), r.mon)
                    val back = SaveEditor(Gen3Save.parse(editor.toBytes()), toRom).box(0)[0]!!
                    println("   ${s.speciesName} Lv${s.level} ${s.moveNames} ${s.itemName} -> " +
                        "${toRom.speciesName(back.species)} Lv${editor.level(back)} ${back.moves.filter { it != 0 }.map(toRom::moveName)} " +
                        "${toRom.itemName(back.heldItem)} ${r.notes}")
                    assertEquals(Transfer.normalize(s.speciesName), Transfer.normalize(toRom.speciesName(back.species)))
                    assertEquals(s.level, editor.level(back))
                } catch (e: IllegalArgumentException) {
                    println("   ${s.speciesName}: refused (${e.message})")
                }
            }
        }
    }

    @Test fun namesMatchAcrossSpellings() {
        assertEquals(2, Transfer.find(listOf("-", "x", "Farfetch’d"), "FARFETCH'D"))
        assertEquals(1, Transfer.find(listOf("-", "Nidoran♀", "Nidoran♂"), "NIDORAN♀"))
        assertEquals(1, Transfer.find(listOf("-", "Flabébé"), "FLABEBE"))
    }
}
