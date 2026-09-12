package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
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
    override val version: Int = 2,
    val tactics: CombatTactics = CombatTactics.LEGACY,
) : TaskDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = when {
        version !in 1..2 -> "unsupported $ID definition version $version"
        !leash.isFinite() || leash !in 1.0..32.0 -> "attack leash must be in [1, 32]"
        budget.ticks !in 20..2400 -> "attack duration must be 20..2400 ticks"
        version == 1 && tactics != CombatTactics.LEGACY -> "v1 attack cannot contain tactical options"
        else -> tactics.validationProblem() ?: NavigateTaskDefinition(dimensionId, anchor, budget = budget).validationProblem()
    }
    companion object { const val ID = "samcnpc:attack" }
}

internal data class TaskCombatOutcome(
    val targetUuid: UUID,
    val status: TaskStatus,
    val reason: TaskReason,
    val confirmedKills: Int,
    val detail: String,
)
