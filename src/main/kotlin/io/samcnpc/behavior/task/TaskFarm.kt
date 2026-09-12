package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.core.api.*

/** Crop policy composes the existing finite work lifecycle and real Core primitives. */
internal object TaskFarm {
    fun capture(npc: NpcFacade,d: FarmTaskDefinition): FarmTaskState? {
        if (!HarvestResources.validCounts(HarvestResources.inventoryCounts(npc))) return null
        return FarmTaskState(ProducedResources.capture(npc),d)
    }
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val d=record.active.definition as FarmTaskDefinition
        val s=checkNotNull(record.active.farming)
        val snapshot=npc.snapshot(); record.reconciledPosition=snapshot.position
        if (snapshot.dimensionId != d.dimensionId || world.dimensionId != d.dimensionId || !d.contains(snapshot.position)) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"farm left its fixed world/travel boundary; actual effects retained")
            return NpcActionResult.failed(record.detail)
        }
        FarmAccounting.observe(record,npc)?.let { return FarmAccounting.mismatch(record,it) }
        if (s.reconcileWorld) FarmReconciliation.tick(record,e,npc,world,d,s)?.let { return it }
        when (s.phase) {
            FarmPhase.PREPARE, FarmPhase.SELECT -> return FarmSelection.tick(record,e,npc,world,d,s)
            FarmPhase.HARVEST -> return FarmHarvest.work(record,e,npc,world,d,s)
            FarmPhase.COLLECT -> return FarmHarvest.collect(record,e,npc,world,d,s)
            FarmPhase.SOIL -> return FarmPlanting.soil(record,e,npc,world,d,s)
            FarmPhase.PLANT -> return FarmPlanting.plant(record,e,npc,world,d,s)
            FarmPhase.SUPPLY -> return FarmSupplies.resume(record,e,npc,d,s)
            FarmPhase.WAIT_GROWTH -> {
                npc.stopControl()
                if (s.growthRemaining == 0) return stop(record,e,npc,s,FarmProblem.GROWTH_TIMEOUT,"supported crops did not mature within the supplied wait")
                if (s.nextGrowthCheck == 0) { s.phase=FarmPhase.SELECT; s.cursor=0; return NpcActionResult.running("bounded crop growth recheck due") }
                return NpcActionResult.running("waiting for crop growth; remaining=${s.growthRemaining}; nextCheck=${s.nextGrowthCheck}")
            }
            FarmPhase.DEPOSIT -> {
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
                if (s.deliverable(d) > 0) return ProducedDelivery.tick(record,e,npc,world,d,s,permitted={ s.deliverable(d) },gain=ProducedGain.STOCK)
                s.selectedContainer=null; TaskInventory.resetRoute(e,npc)
                s.phase=if (s.exhausted || s.stop != null || s.goal(d)) FarmPhase.RETURN else FarmPhase.SELECT
                if (s.phase == FarmPhase.RETURN && s.stop == null) { s.reconcileWorld=true; s.reconcileCursor=0 }
            }
            FarmPhase.RETURN -> {
                val destination=d.returnTo
                if (destination != null) {
                    val result=TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,destination,budget=d.budget))
                    if (result.status != NpcActionStatus.SUCCEEDED) return result
                }
                val complete=s.stop == null && s.goal(d)
                val detail="farm ${if (complete) "complete" else "partial"}; crop=${d.work.crop}; harvested=${s.totalHarvests()}; planted=${s.planted.values.sum()}; cycles=${s.cycle}/${d.work.cycles}; delivered=${s.delivered(d)}/${d.quantity}; stop=${s.stop ?: if (complete) "none" else "INSUFFICIENT_YIELD"}; context=${s.stopDetail ?: "none"}"
                if (complete) record.completeActive(TaskReason.FARM_FINISHED,detail) else record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,detail)
                return if (complete) NpcActionResult.succeeded(detail) else NpcActionResult.failed(detail)
            }
        }
        record.detail="farm ${s.phase}; crop=${d.work.crop}; harvested=${s.totalHarvests()}; delivered=${s.delivered(d)}"
        return NpcActionResult.running(record.detail)
    }
    fun stop(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FarmTaskState,why: FarmProblem,detail: String): NpcActionResult {
        s.stop=why; s.exhausted=true; s.reconcileWorld=false; s.reconcileCursor=0
        s.stopDetail="at ${s.target}: $detail".take(TaskRecord.MAX_DETAIL_LENGTH)
        record.detail="farm $why ${s.stopDetail}".take(TaskRecord.MAX_DETAIL_LENGTH)
        e.blockActionId=null; e.blockCompletion=null; e.blockToolId=null; npc.abortBlockBreak()
        s.target=null; s.collectionTicks=0; s.supplyFrame=null; s.selectedContainer=null; s.phase=FarmPhase.DEPOSIT
        TaskInventory.resetRoute(e,npc); HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
        return NpcActionResult.running(record.detail)
    }
    fun cellFinished(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FarmTaskState): NpcActionResult {
        s.target=null; s.collectionTicks=0; s.phase=if (s.preparing) FarmPhase.PREPARE else FarmPhase.SELECT
        TaskInventory.resetRoute(e,npc); HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
        return NpcActionResult.running("physical field step complete; selecting next authorized cell")
    }
}
