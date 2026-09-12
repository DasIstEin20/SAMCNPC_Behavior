package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

internal object PlantingReconciliation {
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: PlantingTaskDefinition,s: PlantingTaskState): NpcActionResult? {
        if (!BehaviorPlanning.admit(world,64,PlanningKind.RECONCILIATION)) return NpcActionResult.running("sapling journal reconciliation queued")
        val plots=s.plots.values.toList()
        for (plot in plots.asSequence().drop(s.reconcileCursor).take(16)) {
            // An unstarted layout has no NPC placement to reconcile. The normal placement
            // step must reobserve its current gaps before any actual item is spent.
            if (plot.placed.isEmpty()) { s.reconcileCursor++; continue }
            val expected=plot.initial+plot.placed
            val blocks=expected.map { p -> world.observeBlock(p) ?: return TaskPlanting.stop(e,npc,s,PlantingProblem.UNOBSERVABLE,"recorded sapling site is unavailable at $p") }
            val matchingSaplings=blocks.all { it.blockId == d.work.species.blockId }
            val grown=plot.complete(d.work.species) && blocks.all { it.blockId == d.work.species.logId }
            if (!matchingSaplings && !grown) return TaskPlanting.stop(e,npc,s,PlantingProblem.CHANGED,"recorded layout ${plot.base} differs from its saplings or matching grown trunk")
            s.reconcileCursor++
        }
        if (s.reconcileCursor < plots.size) return NpcActionResult.running("rechecking ${s.reconcileCursor}/${plots.size} planted layouts")
        s.reconcileWorld=false; s.reconcileCursor=0
        return null
    }
}
