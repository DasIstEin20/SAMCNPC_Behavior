package io.samcnpc.behavior.combat

import io.samcnpc.core.api.*

internal data class CombatEquipmentChoice(val slot: Int, val destination: NpcEquipmentDestination)

/** Pure selection from one immutable inventory observation. The winning task executes one swap. */
internal object CombatEquipment {
    fun choose(snapshot: NpcSnapshot, inventory: List<NpcInventoryEntry>, tactics: CombatTactics, distance: Double): CombatEquipmentChoice? {
        if (snapshot.itemUse != null || snapshot.rangedAttack != null) return null
        val meleeAvailable = inventory.any { it.stack.count > 0 && NpcItemRole.MELEE_WEAPON in it.knowledge.roles }
        val ranged = tactics.allowed != CombatWeaponAllowance.MELEE && hasRanged(inventory, snapshot.equipment) &&
            (preferRanged(tactics, distance, snapshot.equipment.mainHand.combat.rangedSupported) || !meleeAvailable)
        val weapon = inventory.asSequence().filter { it.stack.count > 0 && tactics.preference != CombatWeaponPreference.CURRENT }.filter { entry ->
            if (ranged) canShoot(entry.knowledge, inventory, snapshot.equipment)
            else NpcItemRole.MELEE_WEAPON in entry.knowledge.roles && tactics.allowed != CombatWeaponAllowance.RANGED
        }.sortedWith(compareByDescending<NpcInventoryEntry> { score(it.knowledge, ranged) }
            .thenBy { if (it.slot == snapshot.selectedHotbarSlot) 0 else 1 }.thenBy { it.slot }).firstOrNull()
        if (weapon != null && weapon.slot != snapshot.selectedHotbarSlot) {
            return CombatEquipmentChoice(weapon.slot, NpcEquipmentDestination.MAIN_HAND)
        }
        if (tactics.useShield && !ranged && NpcItemRole.SHIELD !in snapshot.equipment.offHand.roles) {
            val shield = inventory.filter { NpcItemRole.SHIELD in it.knowledge.roles }.minByOrNull { it.slot }
            if (shield != null) return CombatEquipmentChoice(shield.slot, NpcEquipmentDestination.OFF_HAND)
        }
        if (tactics.equipArmor) {
            val current = snapshot.equipment
            val armor = listOf(NpcEquipmentDestination.HEAD to current.head, NpcEquipmentDestination.CHEST to current.chest,
                NpcEquipmentDestination.LEGS to current.legs, NpcEquipmentDestination.FEET to current.feet)
            for ((destination, worn) in armor) {
                val replacement = inventory.filter { it.knowledge.armorDestination == destination }
                    .sortedWith(compareByDescending<NpcInventoryEntry> { armorScore(it.knowledge) }.thenBy { it.slot }).firstOrNull()
                if (replacement != null && armorScore(replacement.knowledge) > armorScore(worn)) return CombatEquipmentChoice(replacement.slot, destination)
            }
        }
        return null
    }

    fun preferRanged(tactics: CombatTactics, distance: Double, currentlyRanged: Boolean = false): Boolean = when (tactics.allowed) {
        CombatWeaponAllowance.RANGED -> true
        CombatWeaponAllowance.MELEE -> false
        CombatWeaponAllowance.BOTH -> tactics.preference == CombatWeaponPreference.RANGED ||
            (tactics.preference == CombatWeaponPreference.AUTO && distance >= tactics.rangedMinDistance + if (currentlyRanged) -1.0 else 1.0)
    }

    fun canShoot(item: NpcItemKnowledge, inventory: List<NpcInventoryEntry>, equipment: NpcEquipmentKnowledge): Boolean =
        item.combat.rangedSupported && (!item.combat.requiresArrow ||
            NpcItemRole.AMMUNITION in equipment.ammunition.roles || inventory.any { NpcItemRole.AMMUNITION in it.knowledge.roles && it.stack.count > 0 })

    fun hasRanged(inventory: List<NpcInventoryEntry>, equipment: NpcEquipmentKnowledge): Boolean =
        canShoot(equipment.mainHand, inventory, equipment) || inventory.any { canShoot(it.knowledge, inventory, equipment) }

    fun healingItem(inventory: List<NpcInventoryEntry>): NpcInventoryEntry? = inventory
        .filter { it.knowledge.combat.healingConsumable && it.knowledge.combat.useTicks in 1..1200 && it.stack.count > 0 }
        .sortedWith(compareByDescending<NpcInventoryEntry> { it.knowledge.combat.healingStrength }
            .thenBy { it.knowledge.combat.useTicks }.thenBy { it.slot }).firstOrNull()

    private fun score(item: NpcItemKnowledge, ranged: Boolean): Double {
        if (ranged) return when (item.combat.rangedWeapon) {
            NpcRangedWeaponKind.BOW -> 3.0
            NpcRangedWeaponKind.CROSSBOW -> 2.0
            NpcRangedWeaponKind.TRIDENT -> 1.0
            null -> 0.0
        }
        return (1.0 + item.combat.meleeDamageAddition).coerceAtLeast(0.0) *
            (4.0 + item.combat.attackSpeedAddition).coerceIn(0.1, 16.0)
    }
    private fun armorScore(item: NpcItemKnowledge): Double = item.combat.armorDefense * 10.0 + item.combat.armorToughness
}