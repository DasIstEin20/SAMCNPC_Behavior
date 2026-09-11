package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition
import java.util.UUID

/** One supplied UUID, one fixed anchor/deadline; missing targets never authorize substitution. */
internal data class AttackTaskDefinition(
    override val dimensionId: String,
    val targetUuid: UUID,
    val anchor: NpcPosition,
    val leash: Double = 24.0,
    val allowPlayers: Boolean = false,
    override val budget: TaskBudget = TaskBudget(ticks = 600),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported $ID definition version $version"
        !leash.isFinite() || leash !in 1.0..32.0 -> "attack leash must be in [1, 32]"
        budget.ticks !in 20..2400 -> "attack duration must be 20..2400 ticks"
        else -> NavigateTaskDefinition(dimensionId, anchor, budget = budget).validationProblem()
    }
    companion object { const val ID = "samcnpc:attack" }
}

internal enum class TaskReactionMode { PASSIVE, RETALIATE }
internal data class TaskReactionPolicy(
    val mode: TaskReactionMode = TaskReactionMode.PASSIVE,
    val leash: Double = 24.0,
    val durationTicks: Int = 600,
    val cooldownTicks: Int = 40,
    val allowPlayers: Boolean = false,
) {
    fun validationProblem(): String? = when {
        !leash.isFinite() || leash !in 1.0..32.0 -> "reaction leash must be in [1, 32]"
        durationTicks !in 20..2400 -> "reaction duration must be 20..2400 ticks"
        cooldownTicks !in 20..200 -> "reaction cooldown must be 20..200 ticks"
        else -> null
    }
}

internal class TaskReactionState(
    var policy: TaskReactionPolicy = TaskReactionPolicy(),
    var cooldownRemaining: Int = 0,
) {
    /** Core damage identity is transient too; loading cannot replay a pre-load accepted hit. */
    var handledDamage: UUID? = null
}

internal data class TaskCombatOutcome(
    val targetUuid: UUID,
    val status: TaskStatus,
    val reason: TaskReason,
    val confirmedKills: Int,
    val detail: String,
)
