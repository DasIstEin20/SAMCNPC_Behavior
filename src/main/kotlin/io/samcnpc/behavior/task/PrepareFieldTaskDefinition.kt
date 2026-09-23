package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition

/** Every permitted position denotes soil, not a crop cell or a produced-item quota. */
internal data class PrepareFieldTaskDefinition(
    override val dimensionId: String,
    val area: WorkArea,
    override val anchor: NpcPosition,
    override val travelRadius: Double = 32.0,
    override val returnTo: NpcPosition? = null,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : LocalWorkDefinition {
    override val operationId = ID
    val cells: List<NpcBlockPosition> = if (area.validationProblem() == null && area.bounds.height == 1 && area.columns in 1..64)
        java.util.List.copyOf((0 until area.columns).map(area::column).filter(area::contains)) else emptyList()

    override fun validationProblem(): String? {
        val problem=area.validationProblem() ?: NavigateTaskDefinition(dimensionId,anchor,budget=budget).validationProblem()
        if (problem != null) return problem
        if (version != 1 || cells.isEmpty()) return "field requires version1 and a nonempty soil plane of at most64 cells"
        if (!travelRadius.isFinite() || travelRadius !in 4.0..64.0) return "field travel radius must be4..64 blocks"
        val points=cells.map { NpcPosition(it.x+0.5,it.y+1.0,it.z+0.5) }+listOfNotNull(anchor,returnTo)
        for (point in points) {
            NavigateTaskDefinition(dimensionId,point,budget=budget).validationProblem()?.let { return it }
            if (!contains(point)) return "field and return must remain inside the fixed travel boundary"
        }
        if (points.any { a -> points.any { b -> TaskNavigator.distanceSquared(a,b)>58.0*58.0 } })
            return "field endpoints exceed bounded local observations"
        return null
    }
    companion object { const val ID="samcnpc:prepare_field" }
}

internal enum class FieldPhase { WORK, RETURN, VERIFY, DONE }
internal enum class FieldProblem { UNOBSERVABLE, BLOCKED, INVALID_SOIL, MISSING_HOE, UNREACHABLE, CHANGED_LIMIT, NATIVE_USE_FAILED }

internal class FieldPreparationState(val resources: HarvestResources) {
    var phase=FieldPhase.WORK
    var cursor=0
    val confirmed=linkedSetOf<NpcBlockPosition>()
    val attempts=linkedMapOf<NpcBlockPosition,Int>()
    var stop: FieldProblem?=null
    var detail=""
    var reconcileWorld=false
    var reconcileCursor=0
    companion object { const val MAX_ATTEMPTS_PER_CELL=2 }
}
