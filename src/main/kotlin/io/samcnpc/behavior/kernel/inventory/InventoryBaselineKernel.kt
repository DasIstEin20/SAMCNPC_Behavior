package io.samcnpc.behavior.kernel.inventory

import io.samcnpc.core.api.NpcBlockContainerObservation
import io.samcnpc.core.api.NpcFacade

/**
 * Records task-start item counts and later exposes only the stacks collected beyond that baseline.
 * The caller supplies its item classifier, keeping resource policy out of the reusable kernel.
 */
internal object InventoryBaselineKernel {
    fun captureCounts(npc: NpcFacade, includeItem: (String) -> Boolean): Map<String, Int> =
        npc.inventoryContents()
            .asSequence()
            .mapNotNull { entry -> entry.stack.itemId?.takeIf(includeItem)?.let { itemId -> itemId to entry.stack.count } }
            .groupingBy { it.first }
            .fold(0) { total, entry -> total + entry.second }

    fun excessStacks(
        npc: NpcFacade,
        initialCounts: Map<String, Int>,
        includeItem: (String) -> Boolean,
    ): List<InventoryExcessStack> {
        val remainingInitial = initialCounts.toMutableMap()
        return npc.inventoryContents().mapNotNull { entry ->
            val itemId = entry.stack.itemId ?: return@mapNotNull null
            if (!includeItem(itemId)) {
                return@mapNotNull null
            }
            val protectedCount = minOf(entry.stack.count, remainingInitial[itemId] ?: 0)
            if (protectedCount > 0) {
                remainingInitial[itemId] = (remainingInitial[itemId] ?: 0) - protectedCount
            }
            val excessCount = entry.stack.count - protectedCount
            if (excessCount > 0) InventoryExcessStack(entry.slot, itemId, excessCount) else null
        }
    }

    fun NpcBlockContainerObservation.destinationFor(itemId: String?): Int? {
        if (itemId == null) {
            return null
        }
        return slots.firstOrNull { it.stack.itemId == itemId && it.stack.count < it.stack.maxStackSize }?.slot
            ?: slots.firstOrNull { it.stack.isEmpty }?.slot
    }
}

internal data class InventoryExcessStack(val slot: Int, val itemId: String, val count: Int)
