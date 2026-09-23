package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Seed refill uses the existing finite inventory interruption and returns to the captured field work. */
internal object FarmSupplies {
    fun request(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        val sources=d.work.seedSources ?: return TaskFarm.stop(record,e,npc,s,FarmProblem.MISSING_SEEDS,"no seed source was authorized")
        val duration=minOf(1600,record.primary.remainingTicks)
        if (duration < 80) return TaskFarm.stop(record,e,npc,s,FarmProblem.SUPPLY_ENDED,"not enough original task time for seed withdrawal and return")
        val needed=maxOf(1,d.work.cells.size-s.cycleDone.size)
        val target=minOf(2304,d.work.keepSeeds+needed)
        val supply=SupplyStock(listOf(StockNeed(d.work.crop.seedId,d.work.keepSeeds+1,target,d.work.sourceKeepSeeds)),sources)
        val definition=InventoryTaskDefinition(d.dimensionId,supply,d.anchor,npc.snapshot().position,d.travelRadius,workTicks=duration-40,budget=TaskBudget(duration))
        val inventory = when (val captured = InventoryTaskCapture.capture(npc,definition,record.amendments.revision,record)) {
            is InventoryCaptureResult.Captured -> captured.state
            is InventoryCaptureResult.Rejected -> return TaskFarm.stop(record,e,npc,s,FarmProblem.SUPPLY_ENDED,captured.code)
        }
        val problem=record.interrupt(definition)
        if (problem != null) return TaskFarm.stop(record,e,npc,s,FarmProblem.SUPPLY_ENDED,problem)
        record.active.inventory=inventory
        s.supplyFrame=record.active.id; s.phase=FarmPhase.SUPPLY
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("refilling actual seeds through bounded inventory work")
    }
    fun resume(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        val frame=checkNotNull(s.supplyFrame)
        val outcome=record.logistics.outcomes.firstOrNull { it.frameId == frame }
            ?: return FarmAccounting.mismatch(record,"resumed farm supply lacks its exact inventory outcome")
        s.supplyFrame=null
        val seeds=HarvestResources.inventoryCounts(npc)[d.work.crop.seedId] ?: 0
        if (!outcome.returned || seeds <= d.work.keepSeeds) return TaskFarm.stop(record,e,npc,s,FarmProblem.SUPPLY_ENDED,"seed side work ${outcome.reason}; returned=${outcome.returned}; carriedSeeds=$seeds")
        s.phase=FarmPhase.PLANT
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("seed supply returned; revalidating pending planting cell")
    }
}
