package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** A restored or finishing farm rechecks its journal against current blocks without replaying effects. */
internal object FarmReconciliation {
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult? {
        if (!BehaviorPlanning.admit(world,16,PlanningKind.RECONCILIATION)) return NpcActionResult.running("farm effect reconciliation queued")
        val positions=(s.harvested.keys+s.planted.keys).toList()
        for (position in positions.asSequence().drop(s.reconcileCursor).take(16)) {
            val block=world.observeBlock(position) ?: return TaskFarm.stop(record,e,npc,s,FarmProblem.UNOBSERVABLE,"journaled field cell became unavailable")
            val cut=s.harvested[position] ?: 0
            val resown=s.replanted[position] ?: 0
            val expectedAir=cut > 0 && (d.work.mode == FarmMode.HARVEST || cut > resown)
            if (if (expectedAir) !block.isAir else block.blockId != d.work.crop.blockId) return TaskFarm.stop(record,e,npc,s,FarmProblem.CHANGED,"journaled field effect differs from current crop/air at $position")
            s.reconcileCursor++
        }
        if (s.reconcileCursor < positions.size) return NpcActionResult.running("rechecking field effects ${s.reconcileCursor}/${positions.size}")
        s.reconcileWorld=false; s.reconcileCursor=0
        return null
    }
}
