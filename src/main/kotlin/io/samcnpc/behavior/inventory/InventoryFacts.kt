package io.samcnpc.behavior.inventory

import io.samcnpc.behavior.api.ItemQuery
import io.samcnpc.core.api.*

/** A detached bounded observation; main hand aliases inventory and is never added to totals. */
class InventoryFacts(
    inventory: List<NpcInventoryEntry>,
    val equipment: NpcEquipmentSnapshot,
    val knowledge: NpcEquipmentKnowledge,
) {
    val inventory: List<NpcInventoryEntry> = java.util.List.copyOf(inventory)
    init { require(inventory.size == 36 && inventory.map { it.slot }.toSet() == (0..35).toSet()) }
    val freeSlots: Int = inventory.count { it.stack.isEmpty }
    fun count(query: ItemQuery, minimumDurability: Double = 0.0): Int {
        var count = inventory.sumOf { if (matches(query, it.stack, it.knowledge, minimumDurability)) it.stack.count else 0 }
        for (destination in SEPARATE) {
            val (stack, facts) = equipped(destination)
            if (matches(query, stack, facts, minimumDurability)) count += stack.count
        }
        if (matches(query, equipment.ammunition, knowledge.ammunition, minimumDurability)) count += equipment.ammunition.count
        if (matches(query, equipment.totem, knowledge.totem, minimumDurability)) count += equipment.totem.count
        return count
    }
    fun equipped(destination: NpcEquipmentDestination): Pair<NpcItemStackSnapshot, NpcItemKnowledge> = when (destination) {
        NpcEquipmentDestination.MAIN_HAND -> equipment.mainHand to knowledge.mainHand
        NpcEquipmentDestination.OFF_HAND -> equipment.offHand to knowledge.offHand
        NpcEquipmentDestination.HEAD -> equipment.head to knowledge.head
        NpcEquipmentDestination.CHEST -> equipment.chest to knowledge.chest
        NpcEquipmentDestination.LEGS -> equipment.legs to knowledge.legs
        NpcEquipmentDestination.FEET -> equipment.feet to knowledge.feet
    }
    fun equippedMatches(query: ItemQuery, destination: NpcEquipmentDestination, minimumDurability: Double): Boolean {
        val (stack, facts) = equipped(destination)
        return matches(query, stack, facts, minimumDurability)
    }
    companion object {
        private val SEPARATE = listOf(NpcEquipmentDestination.OFF_HAND, NpcEquipmentDestination.HEAD, NpcEquipmentDestination.CHEST, NpcEquipmentDestination.LEGS, NpcEquipmentDestination.FEET)
        fun capture(npc: NpcFacade) = InventoryFacts(npc.inventoryContents(), npc.equipmentContents(), npc.equipmentKnowledge())
        fun matches(query: ItemQuery, stack: NpcItemStackSnapshot, facts: NpcItemKnowledge, minimumDurability: Double) =
            query.matches(stack, facts) && ItemQuery.durability(stack) >= minimumDurability && (stack.maxDamage <= 0 || stack.damage < stack.maxDamage)
    }
}
