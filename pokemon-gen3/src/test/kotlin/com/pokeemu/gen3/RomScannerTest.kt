package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.label
import com.pokeemu.core.Gender
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lays out fake ROMs with the tables the scanner looks for. */
private class FakeRom(size: Int = 0x80000) {
    val bytes = ByteArray(size) { ((it * 7919) shr 3).toByte() } // noise, not zeros
    private var cursor = 0x1000

    fun alloc(n: Int): Int = cursor.also { cursor = (cursor + n + 16 + 3) and 3.inv() } // 4-byte aligned, like real data

    fun text(s: String, at: Int, slot: Int) {
        val enc = Gen3Text.encode(s, slot)
        enc.copyInto(bytes, at)
    }

    /** Inline table: entry i's name at base + i*stride (+ nameOffset). */
    fun inlineNames(names: List<String>, stride: Int, slot: Int, nameOffset: Int = 0): Int {
        val base = alloc(names.size * stride)
        names.forEachIndexed { i, n -> text(n, base + i * stride + nameOffset, slot) }
        return base
    }

    /** Pointer table: entry i holds a pointer to a separately stored string. */
    fun pointerNames(names: List<String>, stride: Int): Int {
        val base = alloc(names.size * stride)
        names.forEachIndexed { i, n ->
            val at = alloc(n.length + 1)
            text(n, at, n.length + 1)
            bytes.put32(base + i * stride, 0x08000000L + at)
        }
        return base
    }
}

private val SPECIES = listOf("??????????", "BULBASAUR", "IVYSAUR", "VENUSAUR", "CHARMANDER", "CHARMELEON", "CHARIZARD",
    "SQUIRTLE", "WARTORTLE", "BLASTOISE", "CATERPIE", "METAPOD", "BUTTERFREE", "WEEDLE", "KAKUNA", "BEEDRILL", "PIDGEY",
    "PIDGEOTTO", "PIDGEOT", "RATTATA", "RATICATE", "SPEAROW", "FEAROW", "EKANS", "ARBOK", "PIKACHU", "RAICHU", "MR. MIME")
private val MOVES = listOf("-------", "POUND", "KARATE CHOP", "DOUBLESLAP", "COMET PUNCH", "MEGA PUNCH")
private val ABILITIES = listOf("-------", "STENCH", "DRIZZLE", "SPEED BOOST", "BATTLE ARMOR")
private val ITEMS = listOf("????????", "MASTER BALL", "ULTRA BALL", "GREAT BALL", "POKé BALL")

/** (species, stats, type1, type2, gender, growth, ability1) for the species the scanner checks. */
private val KNOWN = mapOf(
    1 to listOf(45, 49, 49, 45, 65, 65, 12, 3, 31, 3, 65),
    2 to listOf(60, 62, 63, 60, 80, 80, 12, 3, 31, 3, 65),
    3 to listOf(80, 82, 83, 80, 100, 100, 12, 3, 31, 3, 65),
    4 to listOf(39, 52, 43, 65, 60, 50, 10, 10, 31, 3, 66),
    7 to listOf(44, 48, 65, 43, 50, 64, 11, 11, 31, 3, 67),
    10 to listOf(45, 30, 35, 45, 20, 20, 6, 6, 127, 0, 19),
    16 to listOf(40, 45, 40, 56, 35, 35, 0, 2, 127, 3, 51),
    19 to listOf(30, 56, 35, 72, 25, 35, 0, 0, 127, 0, 50, 62), // Rattata: second ability Guts
    25 to listOf(35, 55, 30, 90, 50, 40, 13, 13, 127, 0, 9),
)

class RomScannerTest {

    @Test fun vanillaLayout() {
        val rom = FakeRom()
        rom.inlineNames(SPECIES, stride = 11, slot = 11)
        rom.inlineNames(MOVES, stride = 13, slot = 13)
        rom.inlineNames(ABILITIES, stride = 13, slot = 13)
        rom.inlineNames(ITEMS, stride = 44, slot = 14) // names at the start of 44-byte item structs
        // Base stats: 28-byte entries; types @6, gender @16, growth @19, abilities @22/23 (u8).
        val bs = rom.alloc(SPECIES.size * 28)
        for (sp in SPECIES.indices) {
            val e = bs + sp * 28
            for (i in 0 until 28) rom.bytes[e + i] = 0
            val k = KNOWN[sp] ?: listOf(50, 50, 50, 50, 50, 50, 0, 0, 127, 0, 34)
            for (i in 0 until 6) rom.bytes[e + i] = k[i].toByte()
            rom.bytes[e + 6] = k[6].toByte(); rom.bytes[e + 7] = k[7].toByte()
            rom.bytes[e + 16] = k[8].toByte(); rom.bytes[e + 17] = 20; rom.bytes[e + 18] = 70
            rom.bytes[e + 19] = k[9].toByte(); rom.bytes[e + 22] = k[10].toByte(); rom.bytes[e + 23] = k.getOrElse(11) { 0 }.toByte()
        }
        // Moves: 12-byte entries: effect, power, type, accuracy, pp.
        val mv = rom.alloc(MOVES.size * 12)
        listOf(listOf(0, 0, 0, 0), listOf(40, 0, 100, 35), listOf(50, 1, 100, 25), listOf(15, 0, 85, 10),
            listOf(18, 0, 85, 15), listOf(80, 0, 85, 20)).forEachIndexed { i, m ->
            m.forEachIndexed { j, v -> rom.bytes[mv + i * 12 + 1 + j] = v.toByte() }
        }

        val data = RomScanner(rom.bytes).scan()
        assertEquals(SPECIES, data.speciesNames.take(SPECIES.size))
        assertEquals("MR. MIME", data.speciesName(27))
        assertEquals(MOVES, data.moveNames.take(MOVES.size))
        assertEquals(ABILITIES, data.abilityNames.take(ABILITIES.size))
        assertEquals("POKé BALL", data.itemName(4))
        val bulba = data.base(1)!!
        assertEquals(listOf(45, 49, 49, 45, 65, 65), bulba.stats)
        assertEquals(12 to 3, bulba.type1 to bulba.type2)
        assertEquals(31, bulba.genderRatio)
        assertEquals(3, bulba.growthRate)
        assertEquals(65, bulba.ability1)
        assertEquals(0, data.base(10)!!.growthRate)
        assertEquals(50 to 62, data.base(19)!!.let { it.ability1 to it.ability2 }, "8-bit ability pair")
        assertTrue(data.report.none { it.contains("16-bit") })
        assertEquals(35, data.basePp(1))
        assertEquals(20, data.basePp(5))
    }

    @Test fun expansionLayoutWithPointersTitleCaseAndWideAbilities() {
        val rom = FakeRom()
        val species = SPECIES.map { it.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }.toMutableList().apply { this[0] = "" }
        rom.pointerNames(species, stride = 4)
        rom.pointerNames(MOVES.map { it.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }, stride = 32)
        // Abilities inline at 17 bytes (ABILITY_NAME_LENGTH 16 + 1).
        rom.inlineNames(ABILITIES, stride = 17, slot = 17)
        // 0x40-byte species info with abilities as u16[3] at 0x18, types at 0x06, gender @0x0E, growth @0x11.
        val bs = rom.alloc(SPECIES.size * 0x40)
        for (sp in SPECIES.indices) {
            val e = bs + sp * 0x40
            for (i in 0 until 0x40) rom.bytes[e + i] = 0
            val k = KNOWN[sp] ?: listOf(50, 50, 50, 50, 50, 50, 0, 0, 127, 0, 34)
            for (i in 0 until 6) rom.bytes[e + i] = k[i].toByte()
            rom.bytes[e + 6] = k[6].toByte(); rom.bytes[e + 7] = k[7].toByte(); rom.bytes[e + 8] = 45
            rom.bytes[e + 0x0E] = k[8].toByte(); rom.bytes[e + 0x0F] = 20; rom.bytes[e + 0x10] = 70
            rom.bytes[e + 0x11] = k[9].toByte()
            rom.bytes.put16(e + 0x18, k[10]); rom.bytes.put16(e + 0x1A, k.getOrElse(11) { 0 }); rom.bytes.put16(e + 0x1C, 34)
        }

        val data = RomScanner(rom.bytes).scan()
        assertEquals("Bulbasaur", data.speciesName(1))
        assertEquals("Mr. Mime", data.speciesName(27))
        assertEquals("Karate Chop", data.moveName(2))
        assertEquals("SPEED BOOST", data.abilityName(3))
        assertTrue(data.itemNames.isEmpty(), "items weren't placed, so they're reported missing")
        assertEquals("Item #4", data.itemName(4))
        val charmander = data.base(4)!!
        assertEquals(66, charmander.ability1)
        assertEquals(0, charmander.ability2)
        assertEquals(50 to 62, data.base(19)!!.let { it.ability1 to it.ability2 }, "u16 ability pair")
        assertEquals(10, charmander.type1)
        assertTrue(data.report.any { it.contains("16-bit") })
    }

    /** Like Pokémon Odyssey: vanilla 28-byte table, but several species rebalanced. */
    @Test fun rebalancedHackStillFound() {
        val rom = FakeRom()
        rom.inlineNames(SPECIES, stride = 11, slot = 11)
        val bs = rom.alloc(SPECIES.size * 28)
        for (sp in SPECIES.indices) {
            val e = bs + sp * 28
            for (i in 0 until 28) rom.bytes[e + i] = 0
            var k = KNOWN[sp] ?: listOf(50, 50, 50, 50, 50, 50, 0, 0, 127, 0, 34)
            // Rebalance: Pikachu and Pidgey get new stats; all genders 50/50; Bulbasaur line gets Chlorophyll first.
            if (sp == 25 || sp == 16) k = listOf(60, 70, 50, 100, 70, 60) + k.drop(6)
            val b = rom.bytes
            for (i in 0 until 6) b[e + i] = k[i].toByte()
            b[e + 6] = k[6].toByte(); b[e + 7] = k[7].toByte(); b[e + 16] = 127
            b[e + 19] = k[9].toByte()
            if (sp in 1..3) { b[e + 22] = 34; b[e + 23] = 65 } else b[e + 22] = k[10].toByte()
        }
        val data = RomScanner(rom.bytes).scan()
        val bulba = data.base(1)!!
        assertEquals(34 to 65, bulba.ability1 to bulba.ability2)
        assertEquals(127, bulba.genderRatio)
        assertEquals(listOf(60, 70, 50, 100, 70, 60), data.base(25)!!.stats)
        assertTrue(data.report.any { "vanilla layout" in it })
    }

    /** Vanilla FireRed: the move-name table starts right after the last species name. */
    @Test fun backToBackTablesDontBleedIntoEachOther() {
        val rom = FakeRom()
        val base = rom.alloc(SPECIES.size * 11 + MOVES.size * 13)
        SPECIES.forEachIndexed { i, n -> rom.text(n, base + i * 11, 11) }
        val movesAt = base + SPECIES.size * 11
        MOVES.forEachIndexed { i, n -> rom.text(n, movesAt + i * 13, 13) }
        val data = RomScanner(rom.bytes).scan()
        assertEquals(SPECIES, data.speciesNames)
        assertEquals(MOVES, data.moveNames.take(MOVES.size))
    }

    @Test fun nothingFoundInNoise() {
        val data = RomScanner(FakeRom(0x20000).bytes).scan()
        assertTrue(data.speciesNames.isEmpty())
        assertTrue(data.baseStats.isEmpty())
        assertEquals("#25", data.speciesName(25))
    }

    @Test fun cacheRoundTrip() {
        val data = RomData(listOf("", "A"), listOf("", "M"), listOf("X"), listOf(), listOf(null, BaseStats(listOf(1, 2, 3, 4, 5, 6), 1, 2, 3, 4, 5, 6)), listOf(0, 35), listOf("note"))
        val out = ByteArrayOutputStream().also { data.write(it) }
        assertEquals(data, RomData.read(ByteArrayInputStream(out.toByteArray())))
    }

    /** Optional: POKEEMU_TEST_ROMS=<folder of .gba files> prints what the scanner finds in real ROMs. */
    @Test fun realRomsIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_ROMS")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("gba", true) }.orEmpty().sortedBy { it.name }) {
            if (f.length() < 0x100000) { println("== ${f.name}: only ${f.length()} bytes, skipped"); continue }
            val t = System.currentTimeMillis()
            val rom = f.readBytes()
            val data = RomScanner(rom).scan()
            println("== ${f.name} (${System.currentTimeMillis() - t} ms)")
            data.report.forEach { println("   $it") }
            println("   #25=${data.speciesName(25)} #277=${data.speciesName(277)} move 1=${data.moveName(1)} item 4=${data.itemName(4)}")
            for (sp in listOf(1, 6, 25, 150, 277, 384)) data.base(sp)?.let { b ->
                println("   ${data.speciesName(sp)}: ${b.stats} ${RomData.typeName(b.type1)}/${RomData.typeName(b.type2)} ${data.abilityName(b.ability1)}/${data.abilityName(b.ability2)} gender=${b.genderRatio} growth=${GameRules.GROWTH_NAMES.getOrElse(b.growthRate) { "?" }}")
            }
            println("   moves 85=${data.moveName(85)} pp=${data.basePp(85)}, 354=${data.moveName(354)}, last=${data.moveNames.lastOrNull()}")
            Gen3Evolutions.locate(rom, Gen3Evolutions.referenced(rom), data.speciesNames.size.coerceAtLeast(412))?.let { (t, stride) ->
                val code = String(rom, 0xAC, 3, Charsets.ISO_8859_1)
                val fixes = Gen3Evolutions.tradeFixes(rom, t, stride, data.speciesNames.size, fixBeauty = code in setOf("BPR", "BPG"), itemName = data::itemName)
                println("   trade fixes (${fixes.size}): " + fixes.joinToString { "${data.speciesName(it.species)}->${data.speciesName(it.target)} ${it.how}" })
            }
            data.learnsets?.let { l ->
                println("   learnsets: ${l.has}")
                for (name in listOf("CHARIZARD", "PIKACHU", "WEAVILE")) {
                    val s = data.speciesNames.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it > 0 } ?: continue
                    val moves = l.learnable(s)
                    println("   $name (#$s): ${moves.size} moves, line ${l.lineage(s).map(data::speciesName)}; " +
                        moves.entries.take(12).joinToString { (m, src) -> "${data.moveName(m)} [${src.label(data::speciesName)}]" })
                }
            }
            data.learnsets?.let { l ->
                println("   TMs (${l.tmMoves.size}, ${l.hmCount} HMs): " + l.tmMoves.take(8).joinToString { data.moveName(it) } + " ... " + l.tmMoves.takeLast(3).joinToString { data.moveName(it) })
            }
            for (name in listOf("FLAMETHROWER", "EARTHQUAKE", "SHADOW BALL", "MOONBLAST")) {
                val m = data.moveNames.indexOfFirst { it.equals(name, true) }.takeIf { it > 0 } ?: continue
                println("   move $name #$m: ${data.moveInfo(m)}")
            }
            System.getenv("PROBE_OUT")?.let { outDir ->
                val sprites = Gen3Sprites(data.sprites) { o, n -> if (o in rom.indices) rom.copyOfRange(o, minOf(rom.size, o + n)) else null }
                val ids = listOf("BULBASAUR", "CHARIZARD", "PIKACHU", "MEWTWO", "TYPHLOSION", "TYRANITAR", "BLAZIKEN", "RAYQUAZA", "GARCHOMP", "LUCARIO", "ARCEUS", "GRENINJA", "SYLVEON", "DRAGAPULT").mapNotNull { n -> data.speciesNames.indexOfFirst { it.equals(n, true) }.takeIf { it > 0 } } + (data.speciesNames.size - 1)
                val pics = ids.flatMap { listOf(runCatching { sprites.pokemon(it, false) }.getOrNull(), runCatching { sprites.pokemon(it, true) }.getOrNull()) } +
                    (1..30).map { runCatching { sprites.item(it) }.getOrNull() } +
                    data.itemNames.indices.filter { data.itemName(it).matches(Regex("(?i)TM0?1|HM0?1")) }.map { runCatching { sprites.item(it) }.getOrNull() }
                println("   sprites: ${pics.count { it != null }}/${pics.size} pictures; species ${ids.map(data::speciesName)}")
                probeSheet(java.io.File(outDir, f.nameWithoutExtension + ".png"), pics)
            }
            assertTrue(data.speciesNames.size > 400, "${f.name}: species table found")
            assertTrue(data.baseStats.isNotEmpty(), "${f.name}: base stats found")
        }
    }
}

/** Test helper: draws pictures in a grid (gray = missing) into a PNG, for checking by eye. */
internal fun probeSheet(out: java.io.File, pics: List<com.pokeemu.core.Picture?>) {
    val cell = 100; val cols = 10; val rows = (pics.size + cols - 1) / cols
    val img = java.awt.image.BufferedImage(cols * cell, rows * cell, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (i in pics.indices) {
        val p = pics[i]; val x0 = i % cols * cell; val y0 = i / cols * cell
        for (y in 0 until cell) for (x in 0 until cell) img.setRGB(x0 + x, y0 + y, if (p == null) 0xFF808080.toInt() else 0xFFF4F4F4.toInt())
        if (p == null) continue
        for (y in 0 until minOf(p.height, cell)) for (x in 0 until minOf(p.width, cell)) {
            val c = p.argb[y * p.width + x]
            if ((c ushr 24) != 0) img.setRGB(x0 + x, y0 + y, c)
        }
    }
    out.parentFile.mkdirs()
    javax.imageio.ImageIO.write(img, "png", out)
}
