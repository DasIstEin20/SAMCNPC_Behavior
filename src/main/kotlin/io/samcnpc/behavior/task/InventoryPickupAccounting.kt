package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraftforge.eventbus.api.SubscribeEvent

/** A physical collection can complete during the approach, before an explicit pickup call. */
internal object InventoryPickupAccounting {
    @SubscribeEvent
    fun picked(event: NpcItemPickupCompletedEvent) {
        val server = BehaviorRuntimeService.serverOrNull() ?: return
        val store = TaskStore.forServer(server)
        val record = store.get(event.npcUuid) ?: return
        if (record.status != TaskStatus.RUNNING && record.status != TaskStatus.WAITING) return
        val definition = record.active.definition as? InventoryTaskDefinition ?: return
        val work = definition.work as? PickupNearby ?: return
        val state = record.active.inventory ?: return
        if (state.phase != InventoryWorkPhase.WORK || state.workRemaining <= 0 || state.steps >= definition.maxSteps ||
            event.dimensionId != definition.dimensionId) return
        val candidate = event.candidate
        val remaining = work.maxItems - state.picked.values.sum()
        if (candidate.itemId !in work.itemIds || candidate.count !in 1..remaining ||
            !definition.contains(candidate.position) ||
            TaskNavigator.distanceSquared(definition.returnTo, candidate.position) > work.radius * work.radius) return
        val service = CoreNpcApi.service(server)
        val npc = service.find(event.npcUuid)?.let(service::runtime) ?: return
        val current = HarvestResources.inventoryCounts(npc)
        val before = current.toMutableMap()
        val previous = (before[candidate.itemId] ?: 0) - event.moved
        val problem = if (previous < 0) "pickup completion exceeds inventory" else {
            if (previous == 0) before.remove(candidate.itemId) else before[candidate.itemId] = previous
            state.resources.reconcileLoad(npc.inventoryLoadSnapshot(), before) ?:
                state.resources.observeLive(before) ?: state.resources.observeLive(current)
        }
        if (problem != null) TaskInventory.mismatch(record, problem)
        else state.picked[candidate.itemId] = (state.picked[candidate.itemId] ?: 0) + event.moved
        store.changed()
    }
}
