package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition

/** v1 retains the published combined chest; v2 makes supply permissions explicit and separate. */
internal data class LumberjackTaskDefinition(
    override val dimensionId: String,
    val area: WorkArea,
    val wood: WoodSelection,
    val destination: NpcBlockPosition,
    val quantity: Int,
    val tools: WorkToolPolicy = WorkToolPolicy.INHERIT_CORE_SETTINGS,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
    val supplySources: ContainerChoices? = null,
    val replant: PlantingTaskDefinition? = null,
) : TaskDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = when {
        version !in 1..2 -> "unsupported lumberjack definition version"
        version == 1 && (supplySources != null || replant != null) -> "v1 lumberjack cannot contain separate supplies"
        supplySources?.validationProblem() != null -> supplySources.validationProblem()
        quantity !in 1..2304 -> "lumberjack delivered minimum must be 1..2304"
        else -> replantProblem() ?: supplyProblem() ?: area.validationProblem() ?: wood.validationProblem() ?: NavigateTaskDefinition(dimensionId,
            NpcPosition(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5), budget = budget).validationProblem()
    }
    private fun replantProblem(): String? {
        val planting=replant ?: return null
        val problem=planting.validationProblem()
        if(problem != null) return problem
        if(planting.dimensionId != dimensionId || planting.work.mode != PlantingMode.GAPS) return "wood replant requires same-dimension gap filling"
        if(!area.contains(planting.work.area.bounds.min) || !area.contains(planting.work.area.bounds.max)) return "replant plane must lie inside the wood work area"
        if(planting.work.sites.flatMap(planting.work.species::footprint).any { !area.contains(it) }) return "replant layout intersects an excluded wood cell"
        if(!planting.contains(TransportTaskDefinition.center(destination))) return "wood output lies outside the fixed replant travel boundary"
        return null
    }
    private fun supplyProblem(): String? {
        for (source in supplySources?.positions.orEmpty()) {
            val problem = NavigateTaskDefinition(dimensionId, TransportTaskDefinition.center(source), budget = budget).validationProblem()
            if (problem != null) return problem
            if (TaskNavigator.distanceSquared(TransportTaskDefinition.center(source), TransportTaskDefinition.center(destination)) > 60.0 * 60.0) return "wood supply and output must be within 60 blocks"
        }
        return null
    }
    companion object { const val ID = "samcnpc:lumberjack" }
}
