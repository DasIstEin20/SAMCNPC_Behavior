package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.kernel.work.PlanningBudget
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.core.api.NpcWorldView
import java.util.UUID

/** Implemented only by the runtime view; direct bounded command observations are outside tick planning. */
internal interface PlanningWorldView {
    fun admitPlanning(units: Int, kind: PlanningKind): Boolean
}
internal object BehaviorPlanning {
    private val budget = PlanningBudget()
    fun admit(world: NpcWorldView, units: Int, kind: PlanningKind): Boolean = (world as? PlanningWorldView)?.admitPlanning(units, kind) ?: true
    fun forNpc(npc: UUID, tick: Long): (Int, PlanningKind) -> Boolean = { units, kind -> budget.acquire(npc, tick, units, kind) }
    fun advance(tick: Long) = budget.advance(tick)
    fun release(npc: UUID) = budget.release(npc)
    fun clear() = budget.clear()
    fun observedQuery() = budget.observedQuery()
    fun statistics() = budget.statistics()
}
