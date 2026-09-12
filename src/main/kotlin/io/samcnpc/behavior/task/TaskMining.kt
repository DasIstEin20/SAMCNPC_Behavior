package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Finite orchestration around observed removal, real collection and real container transfer. */
internal object TaskMining {
    fun capture(npc: NpcFacade): MiningTaskState? {
        if (!HarvestResources.validCounts(HarvestResources.inventoryCounts(npc))) return null
        return MiningTaskState(ProducedResources.capture(npc))
    }
    fun observeInventory(record: TaskRecord,npc: NpcFacade): String? {
        val resources=record.primary.mining?.resources ?: return null
        val counts=HarvestResources.inventoryCounts(npc)
        return resources.reconcileLoad(npc.inventoryLoadSnapshot(),counts) ?: resources.observeLive(counts)
    }
    fun tick(record: TaskRecord,execution: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val definition=record.active.definition as MiningTaskDefinition
        val state=checkNotNull(record.active.mining)
        val snapshot=npc.snapshot(); record.reconciledPosition=snapshot.position
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId || !definition.contains(snapshot.position)) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"mining left its fixed world/travel boundary; actual effects retained")
            return NpcActionResult.failed(record.detail)
        }
        observeInventory(record,npc)?.let { return mismatch(record,it) }
        if (state.reconcileWorld) {
            if (!BehaviorPlanning.admit(world,16,PlanningKind.RECONCILIATION)) return NpcActionResult.running("mining journal reconciliation queued")
            val positions=if (definition.counting == MiningCounting.CLEARED_VOLUME) state.selection.cleared else state.selection.removed.keys
            for (position in positions.asSequence().drop(state.reconcileCursor).take(16)) {
                val block=world.observeBlock(position) ?: return stop(state,execution,npc,MiningProblem.UNOBSERVABLE)
                if (!block.isAir) return stop(state,execution,npc,MiningProblem.CHANGED)
                state.reconcileCursor++
            }
            if (state.reconcileCursor < positions.size) return NpcActionResult.running("rechecking mining journal ${state.reconcileCursor}/${positions.size}")
            state.reconcileWorld=false; state.reconcileCursor=0
        }
        when (state.phase) {
            MiningPhase.SELECT -> {
                if (state.stop != null || state.exhausted || state.goal(definition) ||
                    definition.counting == MiningCounting.DELIVERED_ITEMS && state.delivered(definition)+state.cargo(definition) >= definition.quantity) {
                    state.phase=MiningPhase.DEPOSIT; return NpcActionResult.running("mining selection ended; deliver actual cargo")
                }
                when (val selected=MiningSelection.next(definition.work,state.selection,world)) {
                    is MiningSelectionResult.Target -> { state.target=selected; state.phase=MiningPhase.WORK; execution.rejectedWorkStances.clear(); TaskInventory.resetRoute(execution,npc) }
                    is MiningSelectionResult.Stop -> return stop(state,execution,npc,selected.reason,"selection=${selected.position}")
                    MiningSelectionResult.Exhausted -> { state.exhausted=true; state.phase=MiningPhase.DEPOSIT }
                    MiningSelectionResult.Deferred, MiningSelectionResult.Scanning -> Unit
                }
            }
            MiningPhase.WORK -> return MiningWorkMechanics.work(record,execution,npc,world,definition,state)
            MiningPhase.COLLECT -> return MiningWorkMechanics.collect(record,execution,npc,world,definition,state)
            MiningPhase.DEPOSIT -> {
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
                if (state.cargo(definition) > 0) return ProducedDelivery.tick(record,execution,npc,world,definition,state)
                state.selectedContainer=null; TaskInventory.resetRoute(execution,npc)
                if (state.stop == null && !state.exhausted && !state.goal(definition)) state.phase=MiningPhase.SELECT
                else { state.phase=MiningPhase.RETURN; state.reconcileWorld=state.stop == null; state.reconcileCursor=0 }
            }
            MiningPhase.RETURN -> {
                if (state.stop == null && !state.exhausted && !state.goal(definition)) { state.phase=MiningPhase.SELECT; return NpcActionResult.running("updated mining quota requires more work") }
                val destination=definition.returnTo
                if (destination != null) {
                    val action=TaskNavigator.move(record,execution,npc,world,NavigateTaskDefinition(definition.dimensionId,destination,budget=definition.budget))
                    if (action.status != NpcActionStatus.SUCCEEDED) return action
                }
                val complete=state.stop == null && state.goal(definition)
                val detail="mining ${if (complete) "complete" else "partial"}: method=${definition.work.method}; removed=${state.removedResources(definition)}; delivered=${state.delivered(definition)}; stop=${state.stop ?: if (complete) "none" else "NO_RESOURCE"}; selectionProblems=${state.selection.problems}; context=${state.stopDetail}"
                if (complete) record.completeActive(TaskReason.MINING_FINISHED,detail)
                else record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,detail)
                return if (complete) NpcActionResult.succeeded(detail) else NpcActionResult.failed(detail)
            }
        }
        record.detail="mining ${state.phase}; examined=${state.selection.cursor}; removed=${state.removedResources(definition)}; delivered=${state.delivered(definition)}"
        return NpcActionResult.running(record.detail)
    }
    fun stop(state: MiningTaskState,execution: TaskExecution,npc: NpcFacade,problem: MiningProblem,detail: String = ""): NpcActionResult {
        state.stopDetail = ("target=${state.target?.position}; " + detail).take(256)
        state.stop=problem; state.selection.problem(problem); state.reconcileWorld=false
        state.phase=MiningPhase.DEPOSIT; state.target=null
        execution.blockActionId=null; execution.blockCompletion=null; npc.abortBlockBreak()
        TaskInventory.resetRoute(execution,npc); HarvestWorkClaims.kernel.release(npc.npcUuid)
        return NpcActionResult.running("mining stopped: $problem; delivering real partial cargo")
    }
    fun mismatch(record: TaskRecord,detail: String): NpcActionResult {
        record.primary.mining?.resources?.physical?.uncertain=true
        record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,detail)
        return NpcActionResult.failed(detail)
    }
}
