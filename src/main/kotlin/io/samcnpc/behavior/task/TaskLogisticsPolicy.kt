package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition

/** Disabled unless the summoner explicitly authorizes bounded side work by amendment. */
internal data class TaskLogisticsPolicy(
    val anchor: NpcPosition? = null,
    val supply: SupplyStock? = null,
    val unload: UnloadExcess? = null,
    val pickup: PickupNearby? = null,
    val travelRadius: Double = 16.0,
    val workTicks: Int = 600,
    val durationTicks: Int = 1200,
    val cooldownTicks: Int = 200,
    val maxSteps: Int = 128,
) {
    val enabled: Boolean get() = supply != null || unload != null || pickup != null
    fun validationProblem(): String? {
        if (cooldownTicks !in 20..6000) return "inventory interruption cooldown must be 20..6000 ticks"
        if (!travelRadius.isFinite() || travelRadius !in 4.0..64.0 || workTicks !in 20..36000 ||
            durationTicks !in 40..72000 || workTicks > durationTicks - 20 || maxSteps !in 1..128) return "invalid bounded logistics travel/time/action limits"
        if (!enabled) return if (anchor != null) "disabled logistics must not retain a travel anchor" else null
        val origin = anchor ?: return "enabled logistics requires a fixed travel anchor"
        for (work in listOfNotNull(supply, unload, pickup)) {
            val problem = InventoryTaskDefinition("minecraft:overworld", work, origin, travelRadius = travelRadius,
                workTicks = workTicks, maxSteps = maxSteps, budget = TaskBudget(durationTicks)).validationProblem()
            if (problem != null) return problem
        }
        val unloadById = unload?.reserves?.associateBy { it.itemId }.orEmpty()
        if (supply?.needs.orEmpty().any { need -> unloadById[need.itemId]?.keep?.let { it < need.target } == true }) {
            return "unload reserve must be at least the supply target for the same item"
        }
        return null
    }
}
internal class TaskLogisticsState(
    var policy: TaskLogisticsPolicy = TaskLogisticsPolicy(),
    var cooldownRemaining: Int = 0,
    val outcomes: MutableList<InventoryWorkOutcome> = mutableListOf(),
)
