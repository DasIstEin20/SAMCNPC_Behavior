package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.*
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** The same physical step executor serves a direct order and the final lumberjack replant stage. */
internal object TaskPlanting {
    fun capture(npc: NpcFacade): PlantingTaskState? = if (HarvestResources.validCounts(HarvestResources.inventoryCounts(npc))) PlantingTaskState(HarvestResources.capture(npc)) else null
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val d=record.primary.definition as PlantingTaskDefinition; val s=checkNotNull(record.primary.planting)
        val action=step(record,e,npc,world,d,s)
        if (!record.status.terminal && s.phase == PlantingPhase.DONE) {
            if (s.stop == null && s.goal(d)) record.completeActive(TaskReason.PLANTING_FINISHED,s.detail)
            else record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,s.detail)
        }
        return action
    }
    fun step(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: PlantingTaskDefinition,s: PlantingTaskState): NpcActionResult {
        val snapshot=npc.snapshot(); record.reconciledPosition=snapshot.position
        if (snapshot.dimensionId != d.dimensionId || world.dimensionId != d.dimensionId || !d.contains(snapshot.position)) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"planting left its fixed world/travel boundary; actual planted cells retained")
            return NpcActionResult.failed(record.detail)
        }
        PlantingAccounting.observe(record,npc)?.let { return PlantingAccounting.mismatch(record,s,it) }
        if (s.reconcileWorld) PlantingReconciliation.tick(record,e,npc,world,d,s)?.let { return it }
        when (s.phase) {
            PlantingPhase.SELECT -> {
                if (s.goal(d) || s.cursor == d.work.sites.size) {
                    if (!s.goal(d)) { s.stop=PlantingProblem.NO_ELIGIBLE_SITES; s.detail="finite planting search ended below quota; skipped=${s.skipped.values.groupingBy { it }.eachCount()}".take(TaskRecord.MAX_DETAIL_LENGTH) }
                    s.phase=PlantingPhase.RETURN; s.reconcileWorld=true; s.reconcileCursor=0; TaskInventory.resetRoute(e,npc)
                    return NpcActionResult.running("returning after actual planting work")
                }
                if (!BehaviorPlanning.admit(world,PlantingSites.cost(d.work.species),PlanningKind.GENERAL)) return NpcActionResult.running("bounded planting-site observation queued")
                val base=d.work.sites[s.cursor]; val observation=PlantingSites.observe(world,d.work,base)
                if (observation.problem == PlantingProblem.UNOBSERVABLE) return stop(e,npc,s,observation.problem,observation.detail)
                s.cursor++
                if (observation.problem != null) {
                    s.skipped[base]=observation.problem; s.detail=observation.detail.take(TaskRecord.MAX_DETAIL_LENGTH)
                    return NpcActionResult.running("skipped planting site: ${observation.problem}; ${s.detail}")
                }
                s.plots[base]=PlantingPlot(base,observation.initial); s.selected=base; s.phase=PlantingPhase.PLACE
                e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("eligible planting layout selected at $base")
            }
            PlantingPhase.PLACE -> return PlantingPlacement.tick(record,e,npc,world,d,s)
            PlantingPhase.SUPPLY -> return PlantingSupplies.resume(record,e,npc,d,s)
            PlantingPhase.RETURN -> {
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
                if (d.returnTo != null) {
                    val action=TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,d.returnTo,budget=d.budget))
                    if (action.status != NpcActionStatus.SUCCEEDED) return action
                }
                s.phase=PlantingPhase.DONE
                val complete=s.stop == null && s.goal(d)
                s.detail="planting ${if (complete) "complete" else "partial"}; saplings=${s.planted()}; layouts=${s.completed(d)}/${d.quantity}; skipped=${s.skipped.size}; stop=${s.stop}; ${s.detail}".take(TaskRecord.MAX_DETAIL_LENGTH)
                return if (complete) NpcActionResult.succeeded(s.detail) else NpcActionResult.failed(s.detail)
            }
            PlantingPhase.DONE -> return if (s.stop == null && s.goal(d)) NpcActionResult.succeeded(s.detail) else NpcActionResult.failed(s.detail)
        }
    }
    fun stop(e: TaskExecution,npc: NpcFacade,s: PlantingTaskState,why: PlantingProblem,detail: String): NpcActionResult {
        s.stop=why; s.detail=detail.take(TaskRecord.MAX_DETAIL_LENGTH); s.selected=null; s.supplyFrame=null
        s.phase=PlantingPhase.RETURN; s.reconcileWorld=false; s.reconcileCursor=0
        TaskInventory.resetRoute(e,npc); HarvestWorkClaims.kernel.release(npc.npcUuid)
        return NpcActionResult.running("planting partial: $why; ${s.detail}")
    }
}
