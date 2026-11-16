package com.pokeemu.gen3

/** Which family of Gen 3 game wrote a save; decides where data lives inside the sections. */
enum class Gen3Game(val label: String) {
    RUBY_SAPPHIRE("Ruby / Sapphire"),
    EMERALD("Emerald"),
    FIRERED_LEAFGREEN("FireRed / LeafGreen"),
}

/**
 * Offsets of the data the app reads/writes. Vanilla layouts are below; ROM-hack
 * engines that move data (CFRU, expansion) get their own layout later.
 */
data class SaveLayout(
    val game: Gen3Game,
    /** Section 1 offsets. */
    val teamSize: Int,
    val team: Int,
    val money: Int,
    /** Where the money/item XOR key lives in section 0, or null when unencrypted (R/S). */
    val securityKey: Int?,
) {
    companion object {
        val RUBY_SAPPHIRE = SaveLayout(Gen3Game.RUBY_SAPPHIRE, teamSize = 0x234, team = 0x238, money = 0x490, securityKey = null)
        val EMERALD = SaveLayout(Gen3Game.EMERALD, teamSize = 0x234, team = 0x238, money = 0x490, securityKey = 0xAC)
        val FIRERED_LEAFGREEN = SaveLayout(Gen3Game.FIRERED_LEAFGREEN, teamSize = 0x34, team = 0x38, money = 0x290, securityKey = 0xF20)

        fun of(game: Gen3Game) = when (game) {
            Gen3Game.RUBY_SAPPHIRE -> RUBY_SAPPHIRE
            Gen3Game.EMERALD -> EMERALD
            Gen3Game.FIRERED_LEAFGREEN -> FIRERED_LEAFGREEN
        }
    }
}
