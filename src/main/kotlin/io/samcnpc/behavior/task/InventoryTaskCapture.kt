package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

internal object InventoryTaskCapture {
    fun capture(npc: NpcFacade, definition: InventoryTaskDefinition, revision: Int, parent: TaskRecord? = null): InventoryWorkState {
        val resources = HarvestResources.capture(npc)
        val counts = resources.retained()
        val goals = linkedMapOf<String, Int>()
        var available = 2304
        when (val work = definition.work) {
            is SupplyStock -> for (need in work.needs) {
                val count = counts[need.itemId] ?: 0
                if (count < need.minimum) { val goal = minOf(need.target - count, available); if (goal > 0) goals[need.itemId] = goal; available -= goal }
            }
            is UnloadExcess -> for (reserve in work.reserves) {
                if (protected(parent, reserve.itemId)) continue
                val goal = minOf(unloadable(npc, reserve.itemId, reserve.keep), available)
                if (goal > 0) goals[reserve.itemId] = goal
                available -= goal
            }
            is PickupNearby -> Unit
        }
        return InventoryWorkState(resources, goals, revision, definition.workTicks)
    }
    fun protected(record: TaskRecord?, item: String): Boolean = when (val parent = record?.primary?.definition) {
        is DeliveryTaskDefinition -> parent.itemId == item
        is TransportTaskDefinition -> parent.itemId == item
        is PlantingTaskDefinition -> item == parent.work.species.blockId
        is FarmTaskDefinition -> item in parent.work.crop.collectedItems
        is FoodTaskDefinition -> parent.outputs.matches(item)
        is MiningTaskDefinition -> parent.outputs.matches(item)
        is LumberjackTaskDefinition -> parent.wood.matches(item) || item == parent.replant?.work?.species?.blockId
        else -> false
    }
    /** The selected hand aliases one inventory slot; all separately equipped stores stay untouched. */
    fun unloadable(npc: NpcFacade, item: String, reserve: Int): Int {
        val selected = npc.snapshot().selectedHotbarSlot
        val carried = npc.inventoryContents().sumOf { if (it.slot != selected && it.stack.itemId == item) it.stack.count else 0 }
        val total = HarvestResources.inventoryCounts(npc)[item] ?: 0
        return minOf(carried, (total - reserve).coerceAtLeast(0))
    }
    fun observe(record: TaskRecord, npc: NpcFacade): String? {
        for (frame in record.frames) {
            val state = frame.inventory ?: continue
            val counts = HarvestResources.inventoryCounts(npc)
            val problem = state.resources.reconcileLoad(npc.inventoryLoadSnapshot(), counts) ?: state.resources.observeLive(counts)
            if (problem != null) return problem
        }
        return null
    }
}
