package io.samcnpc.behavior.inventory

import io.samcnpc.behavior.api.ItemQuery
import io.samcnpc.core.api.*

/** One physical inventory step. Mining speed/drop suitability remains Core's per-block mechanism. */
internal object EquipmentPreparation {
    fun compatible(facts: NpcItemKnowledge, destination: NpcEquipmentDestination): Boolean =
        destination == NpcEquipmentDestination.MAIN_HAND || destination == NpcEquipmentDestination.OFF_HAND || facts.armorDestination == destination

    fun choose(inventory: List<NpcInventoryEntry>, query: ItemQuery, destination: NpcEquipmentDestination,
               minimumDurability: Double, selected: Int): NpcInventoryEntry? = inventory.filter {
        InventoryFacts.matches(query, it.stack, it.knowledge, minimumDurability) && compatible(it.knowledge, destination)
    }.sortedWith(compareByDescending<NpcInventoryEntry> { quality(it.knowledge, destination) }
        .thenByDescending { ItemQuery.durability(it.stack) }
        .thenByDescending { (it.stack.maxDamage - it.stack.damage).coerceAtLeast(0) }
        .thenBy { if (destination == NpcEquipmentDestination.MAIN_HAND && it.slot == selected) 0 else 1 }
        .thenBy { it.slot }).firstOrNull()

    fun ensure(npc: NpcFacade, query: ItemQuery, destination: NpcEquipmentDestination, minimumDurability: Double): NpcActionResult {
        val facts = InventoryFacts.capture(npc)
        if (facts.equippedMatches(query, destination, minimumDurability)) return NpcActionResult.succeeded("EQUIPMENT_READY: fresh equipped item satisfies query")
        val snapshot = npc.snapshot()
        if (snapshot.blockBreak != null || snapshot.itemUse != null || snapshot.rangedAttack != null || snapshot.fishing != null) {
            return NpcActionResult.rejected("EQUIPMENT_BUSY: wait for the current physical action boundary", NpcActionCode.NOT_READY)
        }
        val choice = choose(facts.inventory, query, destination, minimumDurability, snapshot.selectedHotbarSlot)
            ?: return NpcActionResult.rejected("MISSING_EQUIPMENT: no carried usable match", NpcActionCode.MISSING_RESOURCE)
        val result = npc.equipFromInventory(choice.slot, destination)
        if (result.status != NpcActionStatus.SUCCEEDED) return result
        if (!InventoryFacts.capture(npc).equippedMatches(query, destination, minimumDurability)) return NpcActionResult.failed("EQUIPMENT_MISMATCH: swap did not establish the requested equipment")
        return NpcActionResult.succeeded("EQUIPMENT_READY: equipped observed slot ${choice.slot}")
    }

    private fun quality(facts: NpcItemKnowledge, destination: NpcEquipmentDestination): Double =
        if (destination !in setOf(NpcEquipmentDestination.MAIN_HAND, NpcEquipmentDestination.OFF_HAND)) facts.combat.armorDefense * 10.0 + facts.combat.armorToughness
        else if (NpcItemRole.MELEE_WEAPON in facts.roles && !facts.isTool) (1.0 + facts.combat.meleeDamageAddition).coerceAtLeast(0.0) * (4.0 + facts.combat.attackSpeedAddition).coerceIn(0.1, 16.0)
        else 0.0
}
