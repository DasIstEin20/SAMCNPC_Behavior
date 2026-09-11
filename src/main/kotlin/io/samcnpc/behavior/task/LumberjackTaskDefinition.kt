package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition

/** One finite wood run; the supplied container is both permitted equipment source and output. */
internal data class LumberjackTaskDefinition(
    override val dimensionId: String,
    val area: WorkArea,
    val wood: WoodSelection,
    val destination: NpcBlockPosition,
    val quantity: Int,
    val tools: WorkToolPolicy = WorkToolPolicy.INHERIT_CORE_SETTINGS,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported lumberjack definition version"
        quantity !in 1..2304 -> "lumberjack delivered minimum must be 1..2304"
        else -> area.validationProblem() ?: wood.validationProblem() ?: NavigateTaskDefinition(dimensionId,
            NpcPosition(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5), budget = budget).validationProblem()
    }
    companion object { const val ID = "samcnpc:lumberjack" }
}
