package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Reconstruct the physical pre-insertion boundary; unrelated gains stay protected stock. */
internal object ProducedPickupConfirmation {
    fun confirm(resources: ProducedResources,npc: NpcFacade,event: NpcItemPickupCompletedEvent): String? {
        val current=HarvestResources.inventoryCounts(npc)
        val item=event.candidate.itemId
        val before=current.toMutableMap()
        val previous=(before[item] ?: 0)-event.moved
        if (previous < 0) return "pickup completion exceeds physical stock"
        if (previous == 0) before.remove(item) else before[item]=previous
        return resources.reconcileLoad(npc.inventoryLoadSnapshot(),before) ?: resources.observeLive(before,ProducedGain.STOCK) ?: resources.pickup(item,event.moved,current)
    }
}
