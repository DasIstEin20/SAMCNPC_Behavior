package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Travel owns the normal route/job deadline; the short loot wait measures time spent collecting. */
internal object TaskPickupNavigation {
    data class Step(val action: NpcActionResult, val collectionTicks: Int)

    fun move(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView,
             dimensionId: String, standing: NpcPosition, budget: TaskBudget, waitBefore: Int): Step {
        require(waitBefore >= 0)
        if (execution.approach != standing) {
            TaskInventory.resetRoute(execution, npc)
            execution.approach = standing
        }
        val action = TaskNavigator.move(record, execution, npc, world,
            NavigateTaskDefinition(dimensionId, standing, budget = budget))
        // A known drop must survive a long physical approach or fair passage yield. This
        // preserves only the local wait; it never replenishes the job clock or retry budget.
        val inTransit = action.status == NpcActionStatus.ACCEPTED || action.status == NpcActionStatus.RUNNING
        return Step(action, if (inTransit) waitBefore else (waitBefore - 1).coerceAtLeast(0))
    }
}
