package com.pokeemu.core

/** Where a Pokémon lives in the save. */
sealed interface Slot {
    data class Party(val index: Int) : Slot
    data class Box(val box: Int, val index: Int) : Slot
}

/**
 * Names and per-species data read from a ROM. Every table may be empty when it
 * couldn't be found; the UI then shows raw numbers.
 */
interface GameData {
    val speciesNames: List<String>
    val moveNames: List<String>
    val abilityNames: List<String>
    val itemNames: List<String>
    /** Human-readable notes on what was found, for the "ROM data" info panel. */
    val report: List<String>
    fun base(species: Int): BaseStats?
    fun basePp(move: Int): Int?
    /** Which moves each species can learn; null when the ROM's tables weren't found. */
    val learnsets: Learnsets? get() = null

    fun speciesName(id: Int) = speciesNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "#$id"
    fun moveName(id: Int) = if (id == 0) "—" else moveNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Move #$id"
    fun abilityName(id: Int) = abilityNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Ability #$id"
    fun itemName(id: Int) = if (id == 0) "None" else itemNames.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "Item #$id"
    fun typeName(id: Int): String = TYPE_NAMES.getOrNull(id) ?: "Type #$id"

    companion object {
        val TYPE_NAMES = listOf(
            "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "???",
            "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark", "Fairy",
        )

        val NATURES = listOf(
            "Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile", "Relaxed", "Impish", "Lax",
            "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet", "Bashful", "Rash",
            "Calm", "Gentle", "Sassy", "Careful", "Quirky",
        )
    }
}

/** One Pokémon as the editor sees it, whatever the generation. Stats are HP, Atk, Def, Spe, SpA, SpD. */
interface Mon {
    var species: Int
    val nickname: String
    var heldItem: Int
    var experience: Long
    var friendship: Int
    val personality: Long
    val isEgg: Boolean
    val isShiny: Boolean
    val nature: Int
    val trainerId: Int
    val secretId: Int
    val otName: String
    val checksumValid: Boolean
    val moves: List<Int>
    fun move(i: Int): Int
    fun pp(i: Int): Int
    fun setPp(i: Int, pp: Int)
    fun ppUps(i: Int): Int
    fun setPpUps(i: Int, ups: Int)
    fun iv(stat: Int): Int
    fun setIv(stat: Int, value: Int)
    fun ev(stat: Int): Int
    fun setEv(stat: Int, value: Int)
    val ivs: List<Int> get() = (0 until 6).map(::iv)
    val evs: List<Int> get() = (0 until 6).map(::ev)
    /** Party-only fields (null for PC records). */
    val level: Int?
    val currentHp: Int?
    val maxHp: Int?
    /** The ability id this Pokémon has, given its species' base data; null if it can't be told. */
    fun abilityId(base: BaseStats?): Int?
    fun copy(): Mon
}

/** What the battle overlay shows: your party and the opponent's, straight from game RAM. */
data class LiveState(val party: List<Mon>, val opponents: List<Mon>)

/**
 * Editable view of a save's Pokémon. Changes stay in memory until [toBytes];
 * nothing touches the file on disk. Rule violations throw IllegalArgumentException
 * with a message for the user.
 */
interface MonEditor {
    val data: GameData
    val dirty: Boolean
    val partyMembers: List<Mon>
    val boxCount: Int
    val boxSlots: Int
    fun box(b: Int): List<Mon?>
    fun boxName(b: Int): String
    operator fun get(slot: Slot): Mon?

    fun replace(slot: Slot, mon: Mon)
    fun move(from: Slot, to: Slot)
    fun delete(slot: Slot)
    fun insert(slot: Slot, mon: Mon)

    fun level(mon: Mon): Int?
    fun gender(mon: Mon): Gender?
    fun warnings(mon: Mon): List<String>

    fun setLevel(mon: Mon, level: Int)
    fun setSpecies(mon: Mon, species: Int)
    fun setMove(mon: Mon, index: Int, move: Int)
    fun setIdentity(mon: Mon, nature: Int, gender: Gender?, shiny: Boolean)

    /** Ability ids this species can have, and which one [mon] has (-1 if none of them). */
    fun abilityOptions(mon: Mon): List<Int>
    fun abilityIndex(mon: Mon): Int
    fun setAbility(mon: Mon, index: Int)

    val maxNicknameLength: Int
    fun canEncode(text: String): Boolean
    fun setNickname(mon: Mon, text: String)

    /** Single-Pokémon files, e.g. "pk3". */
    val fileExtension: String
    fun exportBytes(mon: Mon): ByteArray
    /** Parses an imported file; throws IllegalArgumentException if it isn't valid. */
    fun parseImport(bytes: ByteArray): Mon

    /** All changes written into the save file's bytes. */
    fun toBytes(): ByteArray
    /** Throws if [written] doesn't parse back to exactly the edited party. */
    fun verify(written: ByteArray)
}
