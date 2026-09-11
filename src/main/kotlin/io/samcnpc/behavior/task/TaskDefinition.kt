package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcPosition
import kotlin.math.abs

/** Closed typed registry: add an operation only with its real executor and validation. */
internal sealed interface TaskDefinition {
    val operationId: String
    val version: Int
    val dimensionId: String
    val budget: TaskBudget
    fun validationProblem(): String?
}

internal data class TaskBudget(val ticks: Int = 6000, val attempts: Int = 3, val backoffTicks: Int = 20) {
    fun validationProblem(): String? = when {
        ticks !in 20..72000 -> "task duration must be 20..72000 ticks"
        attempts !in 1..8 -> "task attempt limit must be 1..8"
        backoffTicks !in 1..200 -> "task retry backoff must be 1..200 ticks"
        else -> null
    }
}

internal data class NavigateTaskDefinition(
    override val dimensionId: String,
    val destination: NpcPosition,
    val speed: Float = 1.0F,
    val arrivalDistance: Double = 0.75,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported $ID definition version $version"
        dimensionId.length > 256 || !DIMENSION_ID.matches(dimensionId) -> "dimension must be a bounded namespaced ID"
        abs(destination.x) > 29_999_984 || abs(destination.z) > 29_999_984 || abs(destination.y) > 2048 -> "destination is outside supported world coordinates"
        else -> budget.validationProblem() ?: NpcNavigationRequest(destination, speed, arrivalDistance).validationProblem()
    }

    companion object {
        const val ID = "samcnpc:navigate"
        private val DIMENSION_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    }
}

/** A quantity from carried stock, with an explicit reserve that unloading cannot consume. */
internal data class DeliveryTaskDefinition(
    override val dimensionId: String,
    val destination: io.samcnpc.core.api.NpcBlockPosition,
    val itemId: String,
    val quantity: Int,
    val keepAtLeast: Int = 0,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported $ID definition version $version"
        itemId.length > 256 || !ITEM_ID.matches(itemId) -> "item must be a bounded namespaced ID"
        quantity !in 1..2304 -> "delivery quantity must be 1..2304"
        keepAtLeast !in 0..2304 -> "retained reserve must be 0..2304"
        else -> NavigateTaskDefinition(dimensionId,
            NpcPosition(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5), budget = budget).validationProblem()
    }
    companion object {
        const val ID = "samcnpc:deliver"
        private val ITEM_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    }
}
