package com.pokeemu.gen5

import com.pokeemu.core.GameRules
import com.pokeemu.core.Gender
import com.pokeemu.core.Mon
import com.pokeemu.core.MonEditor
import com.pokeemu.core.Slot

/**
 * Editable view of a Black/White or Black 2/White 2 save's Pokémon. Changes
 * stay in memory until [toBytes].
 */
class Gen5SaveEditor(private val save: Gen5Save, override val data: Gen5RomData) : MonEditor {

    private val party: MutableList<Pk5> = save.party.map { it.copy() }.toMutableList()
    private val boxes: List<MutableList<Pk5?>> = List(save.boxCount) { b -> save.box(b).toMutableList() }

    override var dirty = false
        private set

    override val partyMembers: List<Pk5> get() = party.toList()
    override val boxCount get() = save.boxCount
    override val boxSlots get() = save.boxSlots
    override fun box(b: Int): List<Pk5?> = boxes[b].toList()
    override fun boxName(b: Int) = save.boxName(b)

    override operator fun get(slot: Slot): Pk5? = when (slot) {
        is Slot.Party -> party.getOrNull(slot.index)
        is Slot.Box -> boxes[slot.box][slot.index]
    }

    private fun canRecalculate(pk: Pk5) = data.base(pk.species) != null

    override fun replace(slot: Slot, mon: Mon) {
        val pk = mon as Pk5
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
        val p = ByteArray(Pk5.BOX_SIZE)
        p.put32(0, kotlin.random.Random.nextLong(0x1_0000_0000L))
        p.put16(0x0C, t.trainerId); p.put16(0x0E, t.secretId)
        p[0x14] = 70
        p[0x17] = own?.get(0x17) ?: 2
        p[0x5F] = own?.get(0x5F) ?: 20
        Pk5.encodeString(t.name, 8).copyInto(p, 0x68)
        own?.copyInto(p, 0x80, 0x80, 0x82) // met where the player's own Pokémon was
        val today = java.time.LocalDate.now()
        p[0x7B] = (today.year - 2000).toByte(); p[0x7C] = today.monthValue.toByte(); p[0x7D] = today.dayOfMonth.toByte()
        p[0x83] = com.pokeemu.core.NewMon.POKE_BALL.toByte()
        p[0x84] = (level.coerceIn(1, 100) or ((own?.get(0x84)?.toInt() ?: 0) and 0x80)).toByte()
        val pk = Pk5.fromPlain(p)
        com.pokeemu.core.NewMon.fill(this, pk, species, level)
        return pk
    }

    override fun insert(slot: Slot, mon: Mon) {
        val pk = mon as Pk5
        require(get(slot) == null) { "That slot isn't empty" }
        if (slot is Slot.Party) {
            require(party.size < 6) { "Your party is full" }
            require(canRecalculate(pk)) { "Can't add this species to the party: its base stats weren't found in the ROM" }
        }
        put(slot, pk)
        party.removeAll { it === EMPTY }
        dirty = true
    }

    private fun put(slot: Slot, pk: Pk5?) {
        when (slot) {
            is Slot.Party -> when {
                pk == null -> if (slot.index < party.size) party[slot.index] = EMPTY
                slot.index < party.size -> party[slot.index] = recalculated(pk.toPartyForm())
                else -> party += recalculated(pk.toPartyForm())
            }
            is Slot.Box -> boxes[slot.box][slot.index] = pk?.toBoxForm()
        }
    }

    private fun recalculated(pk: Pk5): Pk5 {
        if (!pk.isParty) return pk
        val base = data.base(pk.species) ?: return pk
        val level = GameRules.levelForExp(base.growthRate, pk.experience)
        pk.setPartyStats(level, GameRules.stats(base, level, pk.ivs, pk.evs, pk.nature))
        return pk
    }

    override fun level(mon: Mon): Int? {
        val pk = mon as Pk5
        return data.base(pk.species)?.let { GameRules.levelForExp(it.growthRate, pk.experience) } ?: pk.level
    }

    override fun gender(mon: Mon): Gender? {
        val pk = mon as Pk5
        val base = data.base(pk.species) ?: return when {
            pk.isGenderless -> Gender.GENDERLESS
            pk.isFemale -> Gender.FEMALE
            else -> Gender.MALE
        }
        return GameRules.gender(pk.personality, base.genderRatio)
    }

    override fun warnings(mon: Mon): List<String> = buildList {
        val pk = mon as Pk5
        if (!pk.checksumValid) add("Checksum is invalid — the game will show this as a Bad Egg")
        val evTotal = pk.evs.sum()
        if (evTotal > GameRules.MAX_EV_TOTAL) add("EVs total $evTotal (max ${GameRules.MAX_EV_TOTAL})")
        if (data.speciesNames.isNotEmpty() && pk.species !in 1 until data.speciesNames.size) add("Species #${pk.species} isn't in this game")
        if (data.moveNames.isNotEmpty()) pk.moves.filter { it >= data.moveNames.size }.forEach { add("Move #$it isn't in this game") }
        if (pk.moves.all { it == 0 }) add("Has no moves")
        if (data.itemNames.isNotEmpty() && pk.heldItem >= data.itemNames.size) add("Held item #${pk.heldItem} isn't in this game")
        if (data.base(pk.species) == null) add("No base stats for this species in the ROM — level and stats can't be recalculated")
    }

    override fun toBytes(): ByteArray = save.toBytes(party, boxes)

    override fun verify(written: ByteArray) {
        val expected = partyMembers.map { it.toBytes().toList() }
        check(Gen5Save.parse(written).party.map { it.toBytes().toList() } == expected) { "verification failed" }
    }

    override fun setLevel(mon: Mon, level: Int) {
        val pk = mon as Pk5
        val base = requireNotNull(data.base(pk.species)) { "Unknown growth rate for this species" }
        pk.experience = GameRules.expForLevel(base.growthRate, level.coerceIn(1, GameRules.MAX_LEVEL))
    }

    override fun setSpecies(mon: Mon, species: Int) {
        val pk = mon as Pk5
        val oldName = data.speciesName(pk.species)
        val wasDefaultName = !pk.isNicknamed || pk.nickname.equals(oldName, ignoreCase = true)
        val level = data.base(pk.species)?.let { GameRules.levelForExp(it.growthRate, pk.experience) }
        val slot = abilityIndex(pk)
        pk.species = species
        val newBase = data.base(species)
        if (level != null && newBase != null) pk.experience = GameRules.expForLevel(newBase.growthRate, level)
        if (newBase != null) {
            // Keep the same ability slot (first, second or hidden) where the new species has one.
            val options = abilityOptions(pk)
            pk.ability = options.getOrNull(slot.coerceAtLeast(0)) ?: options.firstOrNull() ?: pk.ability
            pk.hasHiddenAbility = pk.ability != 0 && pk.ability == data.hiddenAbilities.getOrNull(species) && pk.ability != newBase.ability1
            syncGenderFlags(pk)
        }
        if (wasDefaultName) {
            pk.setNickname(data.speciesName(species).take(MAX_NICKNAME))
            pk.isNicknamed = false
        }
    }

    override fun setMove(mon: Mon, index: Int, move: Int) {
        val pk = mon as Pk5
        pk.setMove(index, move)
        if (move == 0) {
            pk.setPp(index, 0); pk.setPpUps(index, 0)
            return
        }
        val base = data.basePp(move)
        if (base != null) pk.setPp(index, base + base * pk.ppUps(index) / 5)
        else if (pk.pp(index) == 0) pk.setPp(index, 5)
    }

    override fun setIdentity(mon: Mon, nature: Int, gender: Gender?, shiny: Boolean) {
        val pk = mon as Pk5
        val ratio = data.base(pk.species)?.genderRatio ?: 255
        val g = gender ?: GameRules.gender(pk.personality, ratio)
        pk.personality = GameRules.findPersonality(nature, g, ratio, shiny, pk.trainerId, pk.secretId)
        pk.setNature(nature) // Gen 5 reads the nature from its own byte
        syncGenderFlags(pk)
    }

    private fun syncGenderFlags(pk: Pk5) {
        val base = data.base(pk.species) ?: return
        val g = GameRules.gender(pk.personality, base.genderRatio)
        pk.isGenderless = g == Gender.GENDERLESS
        pk.isFemale = g == Gender.FEMALE
    }

    /** First ability, second (if different), then the hidden one (if any). */
    override fun abilityOptions(mon: Mon): List<Int> {
        val base = data.base(mon.species) ?: return emptyList()
        return buildList {
            add(base.ability1)
            if (base.ability2 != 0 && base.ability2 != base.ability1) add(base.ability2)
            data.hiddenAbilities.getOrNull(mon.species)?.takeIf { it != 0 && it !in this }?.let(::add)
        }
    }
    override fun abilityIndex(mon: Mon): Int = abilityOptions(mon).indexOf((mon as Pk5).ability)
    override fun setAbility(mon: Mon, index: Int) {
        val pk = mon as Pk5
        val options = abilityOptions(pk)
        val id = options.getOrNull(index) ?: return
        pk.ability = id
        pk.hasHiddenAbility = id == data.hiddenAbilities.getOrNull(pk.species) && index == options.lastIndex && options.size > 1
    }

    override val maxNicknameLength get() = MAX_NICKNAME
    override fun canEncode(text: String) = text.all { it.code < 0xF000 }
    override fun setNickname(mon: Mon, text: String) {
        val pk = mon as Pk5
        pk.setNickname(text)
        pk.isNicknamed = !text.equals(data.speciesName(pk.species), ignoreCase = true)
    }

    override val fileExtension get() = "pk5"
    override fun exportBytes(mon: Mon): ByteArray = (mon as Pk5).toBoxForm().toBytes()
    override fun parseImport(bytes: ByteArray): Mon {
        require(bytes.size == Pk5.BOX_SIZE || bytes.size == Pk5.PARTY_SIZE) { "Not a .pk5 file (must be 136 or 220 bytes)" }
        val pk = Pk5(bytes)
        require(pk.checksumValid) { "That .pk5 is corrupted (bad checksum)" }
        return pk
    }

    private companion object {
        const val MAX_NICKNAME = 10
        val EMPTY = Pk5(ByteArray(Pk5.PARTY_SIZE))
    }
}
