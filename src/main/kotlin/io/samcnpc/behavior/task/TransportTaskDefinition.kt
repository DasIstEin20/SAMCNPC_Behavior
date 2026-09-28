package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition

internal enum class ContainerPreference { ORDERED, NEAREST }

/** The immutable allow-list is the permission; preference only ranks those supplied endpoints. */
internal class ContainerChoices(positions: List<NpcBlockPosition>, val preference: ContainerPreference = ContainerPreference.ORDERED) {
    val positions: List<NpcBlockPosition> = java.util.List.copyOf(positions)
    val accessProbes: Map<NpcBlockPosition, io.samcnpc.core.api.NpcStockQuery> =
        if (positions.size in 1..8 && positions.all { it.x in -29999984..29999984 && it.z in -29999984..29999984 })
            java.util.Map.copyOf(positions.associateWith { io.samcnpc.core.api.NpcStockQuery(it, "minecraft:air") }) else emptyMap()
    fun validationProblem(): String? = if (positions.size !in 1..8 || positions.distinct().size != positions.size) "container choices require 1..8 distinct positions" else null
    override fun equals(other: Any?): Boolean = other is ContainerChoices && positions == other.positions && preference == other.preference
    override fun hashCode(): Int = 31 * positions.hashCode() + preference.hashCode()
}

internal data class TransportTaskDefinition(
    override val dimensionId: String,
    val sources: ContainerChoices,
    val destinations: ContainerChoices,
    val itemId: String,
    val quantity: Int,
    val anchor: NpcPosition,
    val travelRadius: Double = 64.0,
    val keepAtLeast: Int = 0,
    val sourceKeepAtLeast: Int = 0,
    val returnTo: NpcPosition? = null,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId: String = ID
    fun contains(position: NpcPosition): Boolean = TaskNavigator.distanceSquared(anchor, position) <= travelRadius * travelRadius
    override fun validationProblem(): String? {
        if (version != 1) return "unsupported transport definition version"
        if (!travelRadius.isFinite() || travelRadius !in 4.0..64.0) return "transport travel radius must be 4..64 blocks"
        if (sourceKeepAtLeast !in 0..2304) return "source reserve must be 0..2304"
        val choices = sources.validationProblem() ?: destinations.validationProblem()
        if (choices != null) return choices
        if (sources.positions.any { it in destinations.positions }) return "goods sources and recipients must be disjoint"
        val common = DeliveryTaskDefinition(dimensionId, destinations.positions.first(), itemId, quantity, keepAtLeast, budget).validationProblem()
            ?: NavigateTaskDefinition(dimensionId, anchor, budget = budget).validationProblem()
        if (common != null) return common
        val positions = (sources.positions + destinations.positions).map { center(it) } + listOfNotNull(anchor, returnTo)
        for (position in positions) {
            val problem = NavigateTaskDefinition(dimensionId, position, budget = budget).validationProblem()
            if (problem != null) return problem
            if (!contains(position)) return "every endpoint/return must be inside the fixed travel boundary"
        }
        if (positions.any { a -> positions.any { b -> TaskNavigator.distanceSquared(a, b) > 60.0 * 60.0 } }) return "transport endpoints must be within 60 blocks of each other for bounded local observation"
        return null
    }
    companion object {
        const val ID = "samcnpc:transport"
        fun center(position: NpcBlockPosition) = NpcPosition(position.x + 0.5, position.y + 0.5, position.z + 0.5)
    }
}
