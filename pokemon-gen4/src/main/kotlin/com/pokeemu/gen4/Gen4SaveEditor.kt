package com.pokeemu.gen4

import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.core.Slot

/**
 * Editable view of a Gen 4 save's Pokémon. Changes stay in memory until
 * [toBytes]. Species without ROM data (e.g. fakemon whose tables weren't found)
 * can be moved around but not have their level or stats recalculated.
 */
class Gen4SaveEditor(private val save: Gen4Save, override val data: Gen4RomData) : MonEditor {

    private val party: MutableList<Pk4> = save.party.map { it.copy() }.toMutableList()
    private val boxes: List<MutableList<Pk4?>> = List(save.layout.boxCount) { b -> save.box(b).toMutableList() }

    override var dirty = false
        private set

    /**
     * Vanilla games store the ability id itself; some hacks with more than 255
     * abilities store something else there (Origin HeartGold: a slot number).
     * Abilities are only editable when the save's Pokémon hold real ids.
     */
    val abilitiesAreIds: Boolean by lazy {
        val checked = (party + boxes.flatten().filterNotNull()).filter { data.base(it.species) != null }
        checked.isNotEmpty() && checked.count { it.ability in abilityOptions(it) } * 2 >= checked.size
    }

    override val partyMembers: List<Pk4> get() = party.toList()
    override val boxCount get() = save.layout.boxCount
    override val boxSlots get() = Gen4Save.BOX_SLOTS
    override fun box(b: Int): List<Pk4?> = boxes[b].toList()
    override fun boxName(b: Int) = save.boxName(b).ifBlank { "Box ${b + 1}" }

    override operator fun get(slot: Slot): Pk4? = when (slot) {
        is Slot.Party -> party.getOrNull(slot.index)
        is Slot.Box -> boxes[slot.box][slot.index]
    }

    private fun canRecalculate(pk: Pk4) = data.base(pk.species) != null

    override fun replace(slot: Slot, mon: Mon) {
        val pk = mon as Pk4
        requireNotNull(get(slot)) { "slot is empty" }
        when (slot) {
            is Slot.Party -> party[slot.index] = recalculated(pk.toPartyForm())
            is Slot.Box -> boxes[slot.box][slot.index] = pk.toBoxForm()
        }
        dirty = true
    }

    override fun move(from: Slot, to: Slot) {
        if (from == to) return
        val a = requireNotNull(get(from)) { "Nothing to move" }
        val b = get(to)
        if (from is Slot.Party && to !is Slot.Party && b == null) require(party.size > 1) { "Your party can't be empty" }
        if (to is Slot.Party && b == null && from !is Slot.Party) require(party.size < 6) { "Your party is full" }
        if (to is Slot.Party && from !is Slot.Party) require(canRecalculate(a)) { "Can't add this species to the party: its base stats weren't found in the ROM" }
        if (from is Slot.Party && to !is Slot.Party && b != null) require(canRecalculate(b)) { "Can't add this species to the party: its base stats weren't found in the ROM" }
        put(to, a)
        put(from, b)
        party.removeAll { it === EMPTY }
        dirty = true
    }

    override fun delete(slot: Slot) {
        requireNotNull(get(slot)) { "slot is empty" }
        if (slot is Slot.Party) require(party.size > 1) { "Your party can't be empty" }
        put(slot, null)
        party.removeAll { it === EMPTY }
        dirty = true
    }

    override fun create(species: Int, level: Int): Mon {
        val t = save.trainer
        val own = party.firstOrNull { it.trainerId == t.trainerId && it.secretId == t.secretId }?.plainBytes()
        val p = ByteArray(Pk4.BOX_SIZE)
        p.put32(0, kotlin.random.Random.nextLong(0x1_0000_0000L))
        p.put16(0x0C, t.trainerId); p.put16(0x0E, t.secretId)
        p[0x14] = 70
        p[0x17] = own?.get(0x17) ?: 2
        p[0x5F] = own?.get(0x5F) ?: 12
        Gen4Text.encode(t.name, 8).copyInto(p, 0x68)
        // Met where the player's own Pokémon was (the Platinum/HGSS and the Diamond/Pearl field).
        own?.copyInto(p, 0x46, 0x46, 0x48)
        own?.copyInto(p, 0x7E, 0x7E, 0x80)
        val today = java.time.LocalDate.now()
        p[0x7B] = (today.year - 2000).toByte(); p[0x7C] = today.monthValue.toByte(); p[0x7D] = today.dayOfMonth.toByte()
        p[0x83] = com.pokeemu.core.NewMon.POKE_BALL.toByte()
        p[0x84] = (level.coerceIn(1, 100) or ((own?.get(0x84)?.toInt() ?: 0) and 0x80)).toByte()
        p[0x86] = com.pokeemu.core.NewMon.POKE_BALL.toByte() // HeartGold/SoulSilver's ball
        val pk = Pk4.fromPlain(p)
        com.pokeemu.core.NewMon.fill(this, pk, species, level)
        return pk
    }

    override fun insert(slot: Slot, mon: Mon) {
        val pk = mon as Pk4
        require(get(slot) == null) { "That slot isn't empty" }
        if (slot is Slot.Party) {
            require(party.size < 6) { "Your party is full" }
            require(canRecalculate(pk)) { "Can't add this species to the party: its base stats weren't found in the ROM" }
        }
        put(slot, pk)
        party.removeAll { it === EMPTY }
        dirty = true
    }

    private fun put(slot: Slot, pk: Pk4?) {
        when (slot) {
            is Slot.Party -> when {
                pk == null -> if (slot.index < party.size) party[slot.index] = EMPTY
                slot.index < party.size -> party[slot.index] = recalculated(pk.toPartyForm())
                else -> party += recalculated(pk.toPartyForm())
            }
            is Slot.Box -> boxes[slot.box][slot.index] = pk?.toBoxForm()
        }
    }

    /** Level from experience and stats from base/IV/EV/nature, for party Pokémon. */
    private fun recalculated(pk: Pk4): Pk4 {
        if (!pk.isParty) return pk
        val base = data.base(pk.species) ?: return pk
        val level = GameRules.levelForExp(base.growthRate, pk.experience)
        pk.setPartyStats(level, GameRules.stats(base, level, pk.ivs, pk.evs, pk.nature))
        return pk
    }

    override fun level(mon: Mon): Int? {
        val pk = mon as Pk4
        return data.base(pk.species)?.let { GameRules.levelForExp(it.growthRate, pk.experience) } ?: pk.level
    }

    override fun gender(mon: Mon): Gender? {
        val pk = mon as Pk4
        val base = data.base(pk.species) ?: return when {
            pk.isGenderless -> Gender.GENDERLESS
            pk.isFemale -> Gender.FEMALE
            else -> Gender.MALE
        }
        return GameRules.gender(pk.personality, base.genderRatio)
    }

    override fun warnings(mon: Mon): List<String> = buildList {
        val pk = mon as Pk4
        if (!pk.checksumValid) add("Checksum is invalid — the game will show this as a Bad Egg")
        val evTotal = pk.evs.sum()
        if (evTotal > GameRules.MAX_EV_TOTAL) add("EVs total $evTotal (max ${GameRules.MAX_EV_TOTAL})")
        if (data.speciesNames.isNotEmpty() && pk.species !in 1 until data.speciesNames.size) add("Species #${pk.species} isn't in this game")
        if (data.moveNames.isNotEmpty()) pk.moves.filter { it >= data.moveNames.size }.forEach { add("Move #$it isn't in this game") }
        if (pk.moves.all { it == 0 }) add("Has no moves")
        if (data.itemNames.isNotEmpty() && pk.heldItem >= data.itemNames.size) add("Held item #${pk.heldItem} isn't in this game")
        if (data.base(pk.species) == null) add("No base stats for this species in the ROM — level and stats can't be recalculated")
    }

    override fun toBytes(): ByteArray {
        save.setParty(party)
        for (b in 0 until boxCount) for (s in 0 until boxSlots) save.setBoxSlot(b, s, boxes[b][s])
        return save.toBytes()
    }

    override fun verify(written: ByteArray) {
        val expected = partyMembers.map { it.toBytes().toList() }
        check(Gen4Save.parse(written).party.map { it.toBytes().toList() } == expected) { "verification failed" }
    }

    // ---- edit helpers ----

    override fun setLevel(mon: Mon, level: Int) {
        val pk = mon as Pk4
        val base = requireNotNull(data.base(pk.species)) { "Unknown growth rate for this species" }
        pk.experience = GameRules.expForLevel(base.growthRate, level.coerceIn(1, GameRules.MAX_LEVEL))
    }

    override fun setSpecies(mon: Mon, species: Int) {
        val pk = mon as Pk4
        val oldName = data.speciesName(pk.species)
        val wasDefaultName = !pk.isNicknamed || pk.nickname.equals(oldName, ignoreCase = true)
        val level = data.base(pk.species)?.let { GameRules.levelForExp(it.growthRate, pk.experience) }
        pk.species = species
        val newBase = data.base(species)
        if (level != null && newBase != null) pk.experience = GameRules.expForLevel(newBase.growthRate, level)
        if (newBase != null) {
            if (abilitiesAreIds && pk.ability != newBase.ability1 && (newBase.ability2 == 0 || pk.ability != newBase.ability2)) {
                pk.ability = newBase.ability1
            }
            syncGenderFlags(pk)
        }
        if (wasDefaultName) {
            val name = data.speciesName(species).take(MAX_NICKNAME)
            if (Gen4Text.canEncode(name)) {
                pk.setNickname(name)
                pk.isNicknamed = false
            }
        }
    }

    override fun setMove(mon: Mon, index: Int, move: Int) {
        val pk = mon as Pk4
        pk.setMove(index, move)
        if (move == 0) {
            pk.setPp(index, 0); pk.setPpUps(index, 0)
            return
        }
        // Hacks don't always ship data for added moves; keep the current PP then.
        val base = data.basePp(move)
        if (base != null) pk.setPp(index, base + base * pk.ppUps(index) / 5)
        else if (pk.pp(index) == 0) pk.setPp(index, 5)
    }

    override fun setIdentity(mon: Mon, nature: Int, gender: Gender?, shiny: Boolean) {
        val pk = mon as Pk4
        val ratio = data.base(pk.species)?.genderRatio ?: 255
        val g = gender ?: GameRules.gender(pk.personality, ratio)
        pk.personality = GameRules.findPersonality(nature, g, ratio, shiny, pk.trainerId, pk.secretId)
        syncGenderFlags(pk)
    }

    /** Gen 4 also stores gender as flags; keep them in line with the personality value. */
    private fun syncGenderFlags(pk: Pk4) {
        val base = data.base(pk.species) ?: return
        val g = GameRules.gender(pk.personality, base.genderRatio)
        pk.isGenderless = g == Gender.GENDERLESS
        pk.isFemale = g == Gender.FEMALE
    }

    override fun abilityOptions(mon: Mon): List<Int> {
        val base = data.base(mon.species) ?: return emptyList()
        return if (base.ability2 != 0 && base.ability2 != base.ability1) listOf(base.ability1, base.ability2) else listOf(base.ability1)
    }
    override fun abilityIndex(mon: Mon): Int = if (abilitiesAreIds) abilityOptions(mon).indexOf((mon as Pk4).ability) else -1
    override fun setAbility(mon: Mon, index: Int) {
        require(abilitiesAreIds) { "This game stores abilities its own way, so they can't be changed here" }
        abilityOptions(mon).getOrNull(index)?.let { (mon as Pk4).ability = it }
    }

    override val maxNicknameLength get() = MAX_NICKNAME
    override fun canEncode(text: String) = Gen4Text.canEncode(text)
    override fun setNickname(mon: Mon, text: String) {
        val pk = mon as Pk4
        pk.setNickname(text)
        pk.isNicknamed = !text.equals(data.speciesName(pk.species), ignoreCase = true)
    }

    override val fileExtension get() = "pk4"
    override fun exportBytes(mon: Mon): ByteArray = (mon as Pk4).toBoxForm().toBytes()
    override fun parseImport(bytes: ByteArray): Mon {
        require(bytes.size == Pk4.BOX_SIZE || bytes.size == Pk4.PARTY_SIZE) { "Not a .pk4 file (must be 136 or 236 bytes)" }
        val pk = Pk4(bytes)
        require(pk.checksumValid) { "That .pk4 is corrupted (bad checksum)" }
        return pk
    }

    private companion object {
        const val MAX_NICKNAME = 10
        /** Marker for a hole in the party while moving; removed right after. */
        val EMPTY = Pk4(ByteArray(Pk4.PARTY_SIZE))
    }
}
