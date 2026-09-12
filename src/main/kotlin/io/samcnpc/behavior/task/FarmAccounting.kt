package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraftforge.eventbus.api.SubscribeEvent

internal object FarmAccounting {
    fun observe(record: TaskRecord,npc: NpcFacade): String? {
        val state=record.primary.farming ?: return null
        val current=HarvestResources.inventoryCounts(npc)
        return state.resources.reconcileLoad(npc.inventoryLoadSnapshot(),current) ?: state.resources.observeLive(current,ProducedGain.STOCK)
    }
    @SubscribeEvent
    fun picked(event: NpcItemPickupCompletedEvent) {
        val server=BehaviorRuntimeService.serverOrNull() ?: return
        val store=TaskStore.forServer(server)
        val record=store.get(event.npcUuid) ?: return
        if (record.status.terminal) return
        val d=record.primary.definition as? FarmTaskDefinition ?: return
        if (event.dimensionId != d.dimensionId || event.candidate.itemId !in d.work.crop.collectedItems || !allowsPickup(d,checkNotNull(record.primary.farming),event.candidate.position)) return
        val s=checkNotNull(record.primary.farming)
        val service=CoreNpcApi.service(server)
        val npc=service.find(event.npcUuid)?.let(service::runtime) ?: return
        val problem=ProducedPickupConfirmation.confirm(s.resources,npc,event)
        if (problem != null) mismatch(record,problem)
        else { val item=event.candidate.itemId; s.pickups[item]=(s.pickups[item] ?: 0)+event.moved }
        store.changed()
    }
    /** Crop loot can drift into the adjacent irrigation cell, one block below the crop plane. */
    fun allowsPickup(d: FarmTaskDefinition,s: FarmTaskState,position: NpcPosition): Boolean {
        val y=d.work.area.bounds.min.y
        if (position.y < y-1.0 || position.y > y+3.0) return false
        val cell=FoodTaskDefinition.cell(position)
        for (x in -1..1) for (z in -1..1) {
            val harvested=NpcBlockPosition(cell.x+x,y,cell.z+z)
            if (harvested in s.harvested && kotlin.math.abs(position.x-harvested.x-0.5) <= 1.5 && kotlin.math.abs(position.z-harvested.z-0.5) <= 1.5) return true
        }
        return false
    }
    fun mismatch(record: TaskRecord,detail: String): NpcActionResult {
        record.primary.farming?.resources?.physical?.uncertain=true
        record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,detail)
        return NpcActionResult.failed(detail)
    }
}
