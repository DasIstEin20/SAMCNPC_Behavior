package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Deterministic bounded frontier policy over fresh Core standing/path observations. */
internal object TaskExplorer {
    fun capture(npc: NpcFacade,d: ExplorerTaskDefinition): ExplorerTaskState? {
        val snapshot=npc.snapshot()
        if (!snapshot.onGround || TaskNavigator.distanceSquared(snapshot.position,d.anchor) > 0.75*0.75) return null
        val standing=npc.worldView().observeStandingSpace(d.anchor) ?: return null
        if (!standing.clear || !standing.supported || standing.inFluid) return null
        return ExplorerTaskState.initial(d)
    }
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val d=record.active.definition as ExplorerTaskDefinition
        val s=checkNotNull(record.active.explorer)
        val snapshot=npc.snapshot()
        record.reconciledPosition=snapshot.position
        if (snapshot.dimensionId != d.dimensionId || world.dimensionId != d.dimensionId) return fail(record,TaskReason.DIMENSION_CHANGED,"explorer left its assigned dimension")
        if (!d.bounds.contains(snapshot.position)) return fail(record,TaskReason.WORK_FAILED,"explorer was displaced outside its fixed expedition bounds")
        if (s.stop == null) {
            val stop=when {
                s.nodes.size >= d.maxCells -> ExplorerStop.CELL_LIMIT
                record.primary.remainingTicks <= 200+s.depth()*100 -> ExplorerStop.RETURN_RESERVE
                else -> null
            }
            if (stop != null) beginReturn(e,npc,d,s,stop)
        }
        if (s.phase != ExplorerPhase.EXPLORE) return travelBack(record,e,npc,world,d,s)
        val pending=s.pending
        if (pending != null) {
            val leg=TaskNavigator.step(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,d.position(pending),budget=d.budget),d.routeBounds)
            return when (leg) {
                TaskNavigationStep.Arrived -> {
                    s.nodes.add(pending);s.cursor=s.nodes.lastIndex;s.pending=null
                    TaskInventory.resetRoute(e,npc)
                    NpcActionResult.running("explorer confirmed visited cell ${s.nodes.size}/${d.maxCells}")
                }
                is TaskNavigationStep.Progress -> leg.action
                is TaskNavigationStep.Failed -> {
                    reject(s,leg.detail);s.pending=null;s.phase=ExplorerPhase.RECOVER
                    TaskInventory.resetRoute(e,npc)
                    NpcActionResult.running("exploration leg rejected; recovering to last confirmed waypoint")
                }
            }
        }
        val current=s.nodes[s.cursor]
        if (!snapshot.onGround || TaskNavigator.distanceSquared(snapshot.position,d.position(current)) > 0.75*0.75) {
            s.phase=ExplorerPhase.RECOVER
            TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("reobserved displacement; recovering exploration waypoint")
        }
        return select(record,npc,world,d,s,current)
    }
    private fun select(record: TaskRecord,npc: NpcFacade,world: NpcWorldView,d: ExplorerTaskDefinition,s: ExplorerTaskState,current: ExplorerNode): NpcActionResult {
        val direction=(0..3).map { (d.heading+it)%4 }.firstOrNull { current.tried and (1 shl it) == 0 }
        if (direction == null) {
            if (current.parent < 0) beginReturn(null,npc,d,s,ExplorerStop.FRONTIER_EXHAUSTED)
            else s.phase=ExplorerPhase.BACKTRACK
            return NpcActionResult.running("explorer exhausted this frontier; following its confirmed return trail")
        }
        val x=current.x+ExplorerTaskState.DX[direction]
        val z=current.z+ExplorerTaskState.DZ[direction]
        val candidate=ExplorerNode(x,z,current.y,s.cursor)
        if (!d.routeBounds.contains(d.position(candidate)) || s.nodes.any { it.x == x && it.z == z }) {
            current.tried=current.tried or (1 shl direction)
            return NpcActionResult.running("explorer skipped an outside or already visited cell")
        }
        if (!BehaviorPlanning.admit(world,5,PlanningKind.GENERAL)) return NpcActionResult.running("exploration standing scan queued in shared planning budget")
        current.tried=current.tried or (1 shl direction)
        val heights=doubleArrayOf(current.y,current.y+0.5,current.y-0.5,current.y+1.0,current.y-1.0)
        for (height in heights) {
            val node=candidate.copy(y=height)
            val feet=d.position(node)
            if (!d.routeBounds.contains(feet)) continue
            val standing=world.observeStandingSpace(feet) ?: continue
            if (!standing.clear || !standing.supported || standing.inFluid) continue
            s.pending=node
            record.detail="explorer selected observed cell ($x,$z); visited=${s.nodes.size}/${d.maxCells}"
            return NpcActionResult.running(record.detail)
        }
        reject(s,"neighboring column has no observed dry supported stance")
        return NpcActionResult.running(s.lastFailure)
    }
    private fun travelBack(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: ExplorerTaskDefinition,s: ExplorerTaskState): NpcActionResult {
        if (s.phase == ExplorerPhase.DONE) return finish(record,npc,s)
        val recovering=s.phase == ExplorerPhase.RECOVER || s.phase == ExplorerPhase.RETURN_RECOVER
        val targetIndex=if (recovering || s.cursor == 0) s.cursor else s.nodes[s.cursor].parent
        val goal=d.position(s.nodes[targetIndex])
        val result=TaskNavigator.step(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,goal,budget=d.budget),d.routeBounds)
        return when (result) {
            TaskNavigationStep.Arrived -> {
                TaskInventory.resetRoute(e,npc)
                when (s.phase) {
                    ExplorerPhase.RECOVER -> s.phase=ExplorerPhase.EXPLORE
                    ExplorerPhase.RETURN_RECOVER -> s.phase=ExplorerPhase.RETURN
                    ExplorerPhase.BACKTRACK -> { s.cursor=targetIndex;s.phase=ExplorerPhase.EXPLORE }
                    ExplorerPhase.RETURN -> {
                        s.cursor=targetIndex
                        if (s.cursor == 0) { s.phase=ExplorerPhase.DONE;return finish(record,npc,s) }
                    }
                    else -> error("invalid explorer return phase ${s.phase}")
                }
                NpcActionResult.running("explorer reobserved return waypoint $targetIndex")
            }
            is TaskNavigationStep.Progress -> result.action
            is TaskNavigationStep.Failed -> {
                TaskInventory.resetRoute(e,npc)
                record.retry(result.reason,"explorer return/recovery is unavailable: ${result.detail}")
                NpcActionResult.running(record.detail)
            }
        }
    }
    private fun beginReturn(e: TaskExecution?,npc: NpcFacade,d: ExplorerTaskDefinition,s: ExplorerTaskState,stop: ExplorerStop) {
        s.stop=stop;s.pending=null
        s.phase=if (TaskNavigator.distanceSquared(npc.snapshot().position,d.position(s.nodes[s.cursor])) > 0.75*0.75)
            ExplorerPhase.RETURN_RECOVER else ExplorerPhase.RETURN
        if (e != null) TaskInventory.resetRoute(e,npc) else npc.stopControl()
    }
    private fun reject(s: ExplorerTaskState,detail: String) { s.rejectedLegs++;s.lastFailure=detail.take(256) }
    private fun finish(record: TaskRecord,npc: NpcFacade,s: ExplorerTaskState): NpcActionResult {
        val definition=record.active.definition as ExplorerTaskDefinition
        val snapshot=npc.snapshot()
        if (!snapshot.onGround || TaskNavigator.distanceSquared(snapshot.position,definition.anchor) > 0.75*0.75) return fail(record,TaskReason.STATE_MISMATCH,"exploration return is not physically confirmed")
        npc.stopControl()
        record.completeActive(TaskReason.EXPLORATION_FINISHED,"explored ${s.nodes.size} confirmed cells; ${s.stop}; returned to anchor; rejected=${s.rejectedLegs}")
        return NpcActionResult.succeeded(record.detail)
    }
    private fun fail(record: TaskRecord,reason: TaskReason,detail: String): NpcActionResult {
        record.finish(TaskStatus.FAILED,reason,detail)
        return NpcActionResult.failed(detail)
    }
}
