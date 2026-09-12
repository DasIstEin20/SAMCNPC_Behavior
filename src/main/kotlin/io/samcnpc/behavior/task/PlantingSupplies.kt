package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

internal object PlantingSupplies {
    fun request(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: PlantingTaskDefinition,s: PlantingTaskState): NpcActionResult {
        val sources=d.work.sources ?: return TaskPlanting.stop(e,npc,s,PlantingProblem.MISSING_SAPLINGS,"no sapling source was authorized")
        val duration=minOf(1600,record.primary.remainingTicks)
        if (duration < 80) return TaskPlanting.stop(e,npc,s,PlantingProblem.SUPPLY_ENDED,"original deadline leaves no bounded supply/return window")
        val selected=checkNotNull(s.selected); val plot=checkNotNull(s.plots[selected])
        val missing=d.work.species.footprint(selected).count { it !in plot.initial && it !in plot.placed }
        val remainingLayouts=(d.quantity-s.completed(d)).coerceAtLeast(1)
        val target=minOf(2304,d.work.keepSaplings+remainingLayouts*d.work.species.layoutSize*d.work.species.layoutSize)
        val work=SupplyStock(listOf(StockNeed(d.work.species.blockId,d.work.keepSaplings+missing,target,d.work.sourceKeep)),sources)
        val definition=InventoryTaskDefinition(d.dimensionId,work,d.anchor,npc.snapshot().position,d.travelRadius,workTicks=duration-40,budget=TaskBudget(duration))
        val captured=InventoryTaskCapture.capture(npc,definition,record.amendments.revision,record)
        val problem=record.interrupt(definition)
        if (problem != null) return TaskPlanting.stop(e,npc,s,PlantingProblem.SUPPLY_ENDED,problem)
        record.active.inventory=captured; s.supplyFrame=record.active.id; s.phase=PlantingPhase.SUPPLY
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("sapling supply uses existing bounded inventory work")
    }
    fun resume(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: PlantingTaskDefinition,s: PlantingTaskState): NpcActionResult {
        val frame=checkNotNull(s.supplyFrame)
        val outcome=record.logistics.outcomes.firstOrNull { it.frameId == frame }
            ?: return PlantingAccounting.mismatch(record,s,"sapling supply resumed without its exact inventory outcome")
        s.supplyFrame=null
        val plot=checkNotNull(s.plots[s.selected]); val missing=d.work.species.footprint(plot.base).count { it !in plot.initial && it !in plot.placed }
        val carried=HarvestResources.inventoryCounts(npc)[d.work.species.blockId] ?: 0
        if (!outcome.returned || carried < d.work.keepSaplings+missing) return TaskPlanting.stop(e,npc,s,PlantingProblem.SUPPLY_ENDED,"sapling supply ${outcome.reason}; returned=${outcome.returned}; need=${d.work.keepSaplings+missing}; carried=$carried")
        s.phase=PlantingPhase.PLACE; TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("actual saplings returned; rechecking the reserved layout")
    }
}
