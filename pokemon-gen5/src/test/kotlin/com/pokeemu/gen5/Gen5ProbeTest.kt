package com.pokeemu.gen5

import com.pokeemu.core.label
import com.pokeemu.gen4.FileRomSource
import com.pokeemu.gen4.Narc
import com.pokeemu.gen4.NdsFileSystem
import java.io.File
import kotlin.test.Test

/** Optional: POKEEMU_TEST_ROMS5=<folder of .nds> prints what is read from Gen 5 ROMs. */
class Gen5ProbeTest {
    @Test fun realRomsIfProvided() {
        val dir = System.getenv("POKEEMU_TEST_ROMS5")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension.equals("nds", true) }.orEmpty().sortedBy { it.name }) {
            val code = FileRomSource(f).use { String(it.read(0x0C, 4), Charsets.ISO_8859_1) }
            if (Gen5Game.forCode(code) == null) continue
            println("== ${f.name} [$code]")
            val data = FileRomSource(f).use { Gen5RomData.scan(it) }
            data.report.forEach { println("   report: $it") }
            for (name in listOf("Bulbasaur", "Charizard", "Pikachu", "Snivy", "Zekrom", "Genesect")) {
                val s = data.speciesNames.indexOfFirst { it.equals(name, true) }
                val b = data.base(s)
                println("   $name #$s: ${b?.stats} ${b?.let { data.typeName(it.type1) + "/" + data.typeName(it.type2) }} ab=${b?.let { data.abilityName(it.ability1) + "/" + data.abilityName(it.ability2) }} hidden=${data.abilityName(data.hiddenAbilities.getOrElse(s) { 0 })}")
            }
            for (m in listOf(1, 53, 89, 247, 559)) println("   move ${data.moveName(m)}: ${data.moveInfo(m)}")
            data.learnsets?.let { l ->
                println("   TMs: ${l.tmMoves.take(5).map(data::moveName)} ... ${l.tmMoves.takeLast(8).map(data::moveName)}")
                for (name in listOf("Charizard", "Serperior")) {
                    val s = data.speciesNames.indexOfFirst { it.equals(name, true) }
                    val learn = l.learnable(s)
                    println("   $name: ${learn.size} moves, line ${l.lineage(s).map(data::speciesName)}: " + learn.entries.take(14).joinToString { (m, src) -> "${data.moveName(m)} [${src.label(data::speciesName)}]" })
                }
            }
            FileRomSource(f).use { rom ->
                val fixes = Gen5Evolutions.tradeFixes(rom) { data.itemName(it) }
                println("   evolve without trading: ${fixes.size} changes: " + fixes.joinToString { "${data.speciesName(it.species)} → ${data.speciesName(it.target)}: ${it.how}" })
            }
            FileRomSource(f).use { rom ->
                val fs = NdsFileSystem(rom)
                val sp = fs.openNarc("a/0/0/4")
                println("   a/0/0/4: ${sp?.size} files")
                if (sp != null) for (i in listOf(20, 22, 29, 31)) {
                    val d = Lz.maybeDecompress(sp[i])
                    println("     #$i ${sp[i].size} -> ${d?.size} bytes ${d?.let { String(it, 0, 4, Charsets.ISO_8859_1) }} hdr=${d?.copyOfRange(0, minOf(0x30, d.size))?.joinToString(" ") { "%02x".format(it) }}")
                }
                if (sp != null) for (i in listOf(26, 27)) println("     ncer#$i " + sp[i].joinToString(" ") { "%02x".format(it) })
                val sprites = Gen5Sprites.open(rom)
                val ids = listOf("Bulbasaur", "Charizard", "Pikachu", "Snivy", "Zekrom", "Genesect").map { n -> data.speciesNames.indexOfFirst { it.equals(n, true) } }
                System.getenv("PROBE_OUT")?.let { out -> probeSheet5(File(out, "gen5-" + f.nameWithoutExtension + ".png"), ids.flatMap { listOf(sprites.pokemon(it, false), sprites.pokemon(it, true)) } +
                    listOf("Master Ball", "Potion", "Leftovers", "Rare Candy", "Fire Stone", "Protector", "Dragon Scale").map { n -> sprites.item(data.itemNames.indexOfFirst { it.equals(n, true) }) }) }
            }
            if (false) FileRomSource(f).use { rom ->
                val fs = NdsFileSystem(rom)
                for (path in listOf("a/0/0/2", "a/0/0/3")) {
                    val narc = fs.read(path)?.let(Narc::parse) ?: continue
                    for (i in 0 until narc.size) {
                        val t = Gen5Text.decode(narc[i]) ?: continue
                        val first = t.drop(1).take(3)
                        if (first.any { it in setOf("Bulbasaur", "Pound", "Master Ball", "Stench", "Normal", "Hardy") })
                            println("   $path #$i: ${t.size} entries: ${t.take(5)} ... ${t.takeLast(2)}")
                    }
                }
            }
        }
    }
}

/** Test helper: pictures in a grid (gray = missing) as a PNG, for checking by eye. */
internal fun probeSheet5(out: File, pics: List<com.pokeemu.core.Picture?>) {
    val cell = 260; val cols = 6; val rows = maxOf(1, (pics.size + cols - 1) / cols)
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
