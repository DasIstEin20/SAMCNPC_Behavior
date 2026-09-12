package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Passive gifts remain stock. Only completed pickups inside the authorized work area earn cargo. */
internal object FoodAccounting {
    fun observe(record: TaskRecord,npc: NpcFacade): String? {
        val state = record.primary.food ?: return null
        val definition = record.primary.definition as FoodTaskDefinition
        for (slot in npc.inventoryContents()) {
            val id=slot.knowledge.itemId
            if (id != null && slot.knowledge.edible && definition.outputs.matches(id)) state.knownFood.add(id)
        }
        val current = HarvestResources.inventoryCounts(npc)
        return state.resources.reconcileLoad(npc.inventoryLoadSnapshot(),current) ?: state.resources.observeLive(current,ProducedGain.STOCK)
    }
    @SubscribeEvent
    fun picked(event: NpcItemPickupCompletedEvent) {
        val server = BehaviorRuntimeService.serverOrNull() ?: return
        val store = TaskStore.forServer(server)
        val record = store.get(event.npcUuid) ?: return
        if (record.status.terminal) return
        val d = record.primary.definition as? FoodTaskDefinition ?: return
        val s = checkNotNull(record.primary.food)
        if (event.dimensionId != d.dimensionId || !event.knowledge.edible || !d.outputs.matches(event.candidate.itemId) || !d.inWork(event.candidate.position)) return
        val service = CoreNpcApi.service(server)
        val npc = service.find(event.npcUuid)?.let(service::runtime) ?: return
        val item=event.candidate.itemId
        val problem=ProducedPickupConfirmation.confirm(s.resources,npc,event)
        if (problem != null) mismatch(record,problem)
        else {
            s.knownFood.add(item)
            s.pickups[item] = (s.pickups[item] ?: 0)+event.moved
        }
        store.changed()
    }
    fun mismatch(record: TaskRecord,detail: String): NpcActionResult {
        record.primary.food?.resources?.physical?.uncertain = true
        record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,detail)
        return NpcActionResult.failed(detail)
    }
}
