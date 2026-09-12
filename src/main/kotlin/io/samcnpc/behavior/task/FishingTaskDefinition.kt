package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition

internal data class FishingTaskDefinition(
    override val dimensionId: String,
    val water: NpcBlockPosition,
    val standing: NpcPosition,
    val catches: Int,
    val anchor: NpcPosition,
    val travelRadius: Double = 32.0,
    val returnTo: NpcPosition? = anchor,
    val pickupWaitTicks: Int = 240,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId = ID
    val maximumCasts: Int get() = maxOf(8, catches * 4)
    fun contains(position: NpcPosition): Boolean = position.x.isFinite() && position.y.isFinite() && position.z.isFinite() &&
        TaskNavigator.distanceSquared(anchor, position) <= travelRadius * travelRadius
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported fishing definition version"
        catches !in 1..64 -> "fishing quota must be 1..64 catches"
        !travelRadius.isFinite() || travelRadius !in 4.0..64.0 -> "fishing travel radius must be 4..64"
        pickupWaitTicks !in 40..1200 || pickupWaitTicks > budget.ticks -> "fishing pickup wait must be 40..1200 ticks inside the task budget"
        NavigateTaskDefinition(dimensionId, anchor, budget = budget).validationProblem() != null -> "invalid fishing anchor, dimension or budget"
        !contains(standing) || !contains(TransportTaskDefinition.center(water)) || returnTo != null && !contains(returnTo) -> "fishing endpoints must remain inside the fixed boundary"
        TaskNavigator.distanceSquared(standing, TransportTaskDefinition.center(water)) > 9.0 * 9.0 -> "fishing standing position must be within nine blocks of supplied water"
        else -> null
    }
    companion object { const val ID = "samcnpc:fish"; const val ROD = "minecraft:fishing_rod" }
}
