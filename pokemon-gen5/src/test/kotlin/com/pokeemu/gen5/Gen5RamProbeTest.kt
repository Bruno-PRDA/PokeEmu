package com.pokeemu.gen5

import kotlin.test.Test

/** Optional: POKEEMU_RAM5=<folder of RAM dumps> prints every party record found and what precedes it. */
class Gen5RamProbeTest {
    @Test fun probe() {
        val dir = System.getenv("POKEEMU_RAM5")?.let { java.io.File(it) } ?: return
        for (f in dir.listFiles { x -> x.extension == "bin" }.orEmpty().sortedBy { it.name }) {
            val ram = f.readBytes()
            val r = Gen5LiveReader(null, null, 700, 600)
            println("== ${f.name}")
            for ((at, pk) in r.scan(ram)) {
                val pre = (8 downTo 1).joinToString(" ") { "%02x".format(ram[at - it]) }
                println("  %06x #%d %s Lv%d hp %d/%d tid %d pid %08x met %d  pre: %s".format(at, pk.species, pk.nickname, pk.level, pk.currentHp, pk.maxHp, pk.trainerId, pk.personality, pk.plainBytes().let { b -> (b[0x80].toInt() and 0xFF) or ((b[0x81].toInt() and 0xFF) shl 8) }, pre))
            }
            println("  state: " + r.read(ram)?.let { s -> "party ${s.party.map { it.species }} foes ${s.opponents.map { it.species }} wild ${s.opponentsWild}" })
        }
    }
}
