package com.pokeemu.core

import kotlin.random.Random

/** The parts of a new Pokémon that work the same in every game (see [MonEditor.create]). */
object NewMon {
    const val POKE_BALL = 4

    /** Moves it would know at [level]: the last four it learns by then (Pound if none are known). */
    fun movesAt(data: GameData, species: Int, level: Int): List<Int> {
        val learned = data.learnsets?.levelUp?.getOrNull(species).orEmpty()
            .filter { (l, m) -> l in 1..level && m in 1 until maxOf(data.moveNames.size, 2) }
            .map { it.second }
        return learned.reversed().distinct().take(4).reversed().ifEmpty { listOf(1) }
    }

    /** Fills in the species-dependent fields of a blank record [mon] made by an editor. */
    fun fill(editor: MonEditor, mon: Mon, species: Int, level: Int, random: Random = Random.Default) {
        require(Transfer.isRealSpecies(editor.data, species)) { "Species #$species doesn't exist in this game" }
        require(editor.data.base(species) != null) { "This species' base stats weren't found in the ROM" }
        mon.species = species
        for (s in 0 until 6) mon.setIv(s, random.nextInt(32))
        editor.setLevel(mon, level)
        movesAt(editor.data, species, level).forEachIndexed { i, m -> editor.setMove(mon, i, m) }
        editor.setIdentity(mon, random.nextInt(25), null, shiny = false)
        val name = editor.data.speciesName(species).take(editor.maxNicknameLength)
        if (editor.canEncode(name)) editor.setNickname(mon, name)
        // One of its regular abilities (never a hidden one).
        val base = editor.data.base(species)!!
        val regular = listOf(base.ability1, base.ability2).filter { it != 0 }.distinct().size
        val abilities = editor.abilityOptions(mon)
        if (abilities.isNotEmpty()) editor.setAbility(mon, random.nextInt(minOf(abilities.size, maxOf(regular, 1))))
    }
}
