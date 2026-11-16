package com.pokeemu.gen3

import com.pokeemu.core.BaseStats
import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.core.Slot

/**
 * Editable view of a save's Pokémon. Changes stay in memory until [toBytes];
 * nothing touches the file on disk.
 */
class SaveEditor(private val save: Gen3Save, val rom: RomData) : MonEditor {
    override val data: RomData get() = rom
    override val boxCount get() = Gen3Save.BOX_COUNT
    override val boxSlots get() = Gen3Save.BOX_SLOTS

    private val party: MutableList<Pk3> = save.party.map { it.copy() }.toMutableList()

    /** Encoding the save uses; imported/created Pokémon are converted to it. */
    val pkEncoding: Pk3Encoding get() = save.pkEncoding
    private val boxes: List<MutableList<Pk3?>> = List(Gen3Save.BOX_COUNT) { b -> save.box(b).toMutableList() }

    override var dirty = false
        private set

    val game get() = save.game
    val trainer get() = save.trainer
    override val partyMembers: List<Pk3> get() = party.toList()
    override fun box(b: Int): List<Pk3?> = boxes[b].toList()
    override fun boxName(b: Int) = save.boxName(b).ifBlank { "Box ${b + 1}" }

    /** Base stats are needed to compute party stats when withdrawing or editing. */
    val canRecalculate: Boolean get() = rom.baseStats.isNotEmpty()

    override operator fun get(slot: Slot): Pk3? = when (slot) {
        is Slot.Party -> party.getOrNull(slot.index)
        is Slot.Box -> boxes[slot.box][slot.index]
    }

    /** Replaces the Pokémon at an occupied slot (after editing a copy). */
    override fun replace(slot: Slot, mon: Mon) {
        val pk = mon as Pk3
        requireNotNull(get(slot)) { "slot is empty" }
        when (slot) {
            is Slot.Party -> party[slot.index] = recalculated(pk.withEncoding(pkEncoding).toPartyForm())
            is Slot.Box -> boxes[slot.box][slot.index] = pk.withEncoding(pkEncoding).toBoxForm()
        }
        dirty = true
    }

    /**
     * Moves [from] onto [to]: swaps if [to] is occupied, otherwise moves.
     * The party is kept compact and can never become empty.
     */
    override fun move(from: Slot, to: Slot) {
        if (from == to) return
        val a = requireNotNull(get(from)) { "Nothing to move" }
        val b = get(to)
        if (from is Slot.Party && to !is Slot.Party && b == null) require(party.size > 1) { "Your party can't be empty" }
        if (to is Slot.Party && b == null && from !is Slot.Party) require(party.size < 6) { "Your party is full" }
        if ((to is Slot.Party) != (from is Slot.Party)) {
            require(canRecalculate) { "Moving between party and PC needs base stats from the ROM, which weren't found" }
        }
        put(to, a)
        put(from, b)
        compactParty()
        dirty = true
    }

    override fun delete(slot: Slot) {
        requireNotNull(get(slot)) { "slot is empty" }
        if (slot is Slot.Party) require(party.size > 1) { "Your party can't be empty" }
        put(slot, null)
        compactParty()
        dirty = true
    }

    /** Places an imported Pokémon into an empty slot. */
    override fun insert(slot: Slot, mon: Mon) {
        val pk = mon as Pk3
        require(get(slot) == null) { "That slot isn't empty" }
        if (slot is Slot.Party) {
            require(party.size < 6) { "Your party is full" }
            require(canRecalculate) { "Adding to the party needs base stats from the ROM" }
        }
        put(slot, pk)
        compactParty()
        dirty = true
    }

    private fun put(slot: Slot, given: Pk3?) {
        val pk = given?.withEncoding(pkEncoding)
        when (slot) {
            is Slot.Party -> when {
                pk == null -> if (slot.index < party.size) party[slot.index] = EMPTY
                slot.index < party.size -> party[slot.index] = recalculated(pk.toPartyForm())
                else -> party += recalculated(pk.toPartyForm())
            }
            is Slot.Box -> boxes[slot.box][slot.index] = pk?.toBoxForm()
        }
    }

    private fun compactParty() {
        party.removeAll { it === EMPTY }
    }

    /** Level from experience and stats from base/IV/EV/nature, for party Pokémon. */
    fun recalculated(pk: Pk3): Pk3 {
        if (!pk.isParty) return pk
        val base = rom.base(pk.species) ?: return pk
        val level = GameRules.levelForExp(base.growthRate, pk.experience)
        pk.setPartyStats(level, GameRules.stats(base, level, pk.ivs, pk.evs, pk.nature))
        return pk
    }

    /**
     * Level implied by experience (what the game will show after saving); falls
     * back to the stored party level when the growth rate is unknown.
     */
    override fun level(mon: Mon): Int? = (mon as Pk3).let { pk -> rom.base(pk.species)?.let { GameRules.levelForExp(it.growthRate, pk.experience) } ?: pk.level }

    override fun gender(mon: Mon): Gender? = rom.base(mon.species)?.let { GameRules.gender(mon.personality, it.genderRatio) }

    /** Things that look wrong or would be impossible in the real game (warnings only). */
    override fun warnings(mon: Mon): List<String> = buildList {
        val pk = mon as Pk3
        if (!pk.checksumValid) add("Checksum is invalid — the game will show this as a Bad Egg")
        val evTotal = pk.evs.sum()
        if (evTotal > GameRules.MAX_EV_TOTAL) add("EVs total $evTotal (max ${GameRules.MAX_EV_TOTAL})")
        if (rom.speciesNames.isNotEmpty() && pk.species !in 1 until rom.speciesNames.size) add("Species #${pk.species} isn't in this game")
        if (rom.moveNames.isNotEmpty()) pk.moves.filter { it >= rom.moveNames.size }.forEach { add("Move #$it isn't in this game") }
        if (pk.moves.all { it == 0 }) add("Has no moves")
        if (rom.itemNames.isNotEmpty() && pk.heldItem >= rom.itemNames.size) add("Held item #${pk.heldItem} isn't in this game")
        val base = rom.base(pk.species)
        if (base != null && pk.abilitySlot == 1 && base.ability2 == 0) add("Second ability slot is empty for this species")
    }

    /** Writes all changes into the save and returns the new file bytes. */
    override fun toBytes(): ByteArray {
        save.setParty(party)
        for (b in 0 until Gen3Save.BOX_COUNT) for (s in 0 until Gen3Save.BOX_SLOTS) save.setBoxSlot(b, s, boxes[b][s])
        return save.toBytes()
    }

    // ---- edit helpers -------------------------------------------------------------------

    /** Sets level by setting experience to the minimum for that level. */
    override fun setLevel(mon: Mon, level: Int) {
        val pk = mon as Pk3
        val base = requireNotNull(rom.base(pk.species)) { "Unknown growth rate for this species" }
        pk.experience = GameRules.expForLevel(base.growthRate, level.coerceIn(1, GameRules.MAX_LEVEL))
    }

    /** Changes species; renames if the Pokémon still had the default name. */
    override fun setSpecies(mon: Mon, species: Int) {
        val pk = mon as Pk3
        val wasDefaultName = rom.speciesNames.isNotEmpty() && pk.nickname.equals(rom.speciesName(pk.species), ignoreCase = true)
        val oldBase = rom.base(pk.species)
        val level = oldBase?.let { GameRules.levelForExp(it.growthRate, pk.experience) }
        pk.species = species
        val newBase = rom.base(species)
        // Keep the level the same across different growth rates.
        if (level != null && newBase != null) pk.experience = GameRules.expForLevel(newBase.growthRate, level)
        if (newBase != null && newBase.ability2 == 0) pk.abilitySlot = 0
        if (wasDefaultName) {
            val name = rom.speciesName(species).uppercase().take(10)
            if (Gen3Text.canEncode(name)) pk.nickname = name
        }
    }

    /** Sets a move and fills its PP (with PP Ups) when base PP is known. */
    override fun setMove(mon: Mon, index: Int, move: Int) {
        val pk = mon as Pk3
        pk.setMove(index, move)
        val base = if (move == 0) 0 else rom.basePp(move) ?: 5
        val ups = (pk.ppBonuses shr (index * 2)) and 3
        pk.setPp(index, base + base * ups / 5)
    }

    /** Regenerates the personality value to change nature/gender/shininess, keeping everything else. */
    override fun setIdentity(mon: Mon, nature: Int, gender: Gender?, shiny: Boolean) {
        val pk = mon as Pk3
        val ratio = rom.base(pk.species)?.genderRatio ?: 255
        val g = gender ?: GameRules.gender(pk.personality, ratio)
        pk.personality = GameRules.findPersonality(nature, g, ratio, shiny, pk.trainerId, pk.secretId)
    }

    override fun abilityOptions(mon: Mon): List<Int> {
        val base = rom.base(mon.species) ?: return emptyList()
        return if (base.ability2 != 0) listOf(base.ability1, base.ability2) else listOf(base.ability1)
    }
    override fun abilityIndex(mon: Mon): Int = (mon as Pk3).abilitySlot
    override fun setAbility(mon: Mon, index: Int) {
        (mon as Pk3).abilitySlot = index.coerceIn(0, 1)
    }

    override val maxNicknameLength get() = 10
    override fun canEncode(text: String) = Gen3Text.canEncode(text)
    override fun setNickname(mon: Mon, text: String) {
        (mon as Pk3).nickname = text
    }

    override val fileExtension get() = "pk3"
    override fun exportBytes(mon: Mon): ByteArray = (mon as Pk3).withEncoding(Pk3Encoding.STANDARD).toBoxBytes()
    override fun parseImport(bytes: ByteArray): Mon {
        require(bytes.size == Pk3.BOX_SIZE || bytes.size == Pk3.PARTY_SIZE) { "Not a .pk3 file (must be 80 or 100 bytes)" }
        val pk = Pk3(bytes)
        require(pk.checksumValid) { "That .pk3 is corrupted (bad checksum)" }
        return pk
    }

    override fun verify(written: ByteArray) {
        val expected = partyMembers.map { it.toBytes().toList() }
        check(Gen3Save.parse(written).party.map { it.toBytes().toList() } == expected) { "verification failed" }
    }

    private companion object {
        /** Marker for a hole in the party while moving; removed by compactParty. */
        val EMPTY = Pk3(ByteArray(Pk3.PARTY_SIZE))
    }
}
