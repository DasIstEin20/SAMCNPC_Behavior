package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcPosition
import java.util.UUID

/** Missions share engagement mechanics but retain distinct goals and completion evidence. */
internal sealed interface CombatMissionDefinition : TaskDefinition {
    val anchor: NpcPosition
    val leash: Double
    val filter: NpcEntityTypeFilter
    val allowPlayers: Boolean
    val tactics: CombatTactics
    val returnTo: NpcPosition
    fun area() = CombatTargetSelector.Area(anchor, leash)

    fun commonProblem(): String? = when {
        version != 1 -> "unsupported $operationId definition version $version"
        !leash.isFinite() || leash !in 1.0..32.0 -> "mission leash must be 1..32 blocks"
        else -> NavigateTaskDefinition(dimensionId, anchor, budget = budget).validationProblem()
            ?: NavigateTaskDefinition(dimensionId, returnTo, budget = budget).validationProblem()
            ?: tactics.validationProblem()
            ?: if (!area().contains(returnTo)) "return point must remain within the mission boundary" else null
    }
}

/** A null subject guards the filtered fixed point/area; a UUID guards only that subject's attackers. */
internal data class DefendTaskDefinition(
    override val dimensionId: String,
    override val anchor: NpcPosition,
    override val leash: Double,
    val subjectUuid: UUID? = null,
    override val filter: NpcEntityTypeFilter = NpcEntityTypeFilter.ANY,
    val dutyTicks: Int = 1200,
    override val returnTo: NpcPosition = anchor,
    override val allowPlayers: Boolean = false,
    override val tactics: CombatTactics = CombatTactics(),
    override val budget: TaskBudget = TaskBudget(ticks = dutyTicks + 400),
    override val version: Int = 1,
) : CombatMissionDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = commonProblem() ?: when {
        subjectUuid == null && filter.isEmpty -> "point/area defense requires an explicit type/tag filter"
        dutyTicks !in 20..71600 || budget.ticks < dutyTicks + 20 -> "defense requires bounded duty time and a separate return allowance"
        else -> null
    }
    companion object { const val ID = "samcnpc:defend" }
}

internal data class AreaAttackTaskDefinition(
    override val dimensionId: String,
    override val anchor: NpcPosition,
    override val leash: Double,
    override val filter: NpcEntityTypeFilter,
    val quota: Int,
    override val returnTo: NpcPosition = anchor,
    override val allowPlayers: Boolean = false,
    override val tactics: CombatTactics = CombatTactics(),
    override val budget: TaskBudget = TaskBudget(ticks = 2400),
    override val version: Int = 1,
) : CombatMissionDefinition {
    override val operationId: String = ID
    override fun validationProblem(): String? = commonProblem() ?: when {
        filter.isEmpty -> "area attack requires an explicit type/tag filter"
        quota !in 1..64 -> "area attack quota must be 1..64 confirmed defeats"
        else -> null
    }
    companion object { const val ID = "samcnpc:attack_area" }
}

internal enum class PatrolReaction { PASSIVE, RETALIATE, PROTECT_SUMMONER, SUPPORT, AREA }

internal class PatrolTaskDefinition(
    override val dimensionId: String,
    override val anchor: NpcPosition,
    override val leash: Double,
    route: List<NpcPosition>,
    val rounds: Int = 1,
    val dwellTicks: Int = 20,
    val reaction: PatrolReaction = PatrolReaction.RETALIATE,
    val subjectUuid: UUID? = null,
    val supportTargetUuid: UUID? = null,
    override val filter: NpcEntityTypeFilter = NpcEntityTypeFilter.ANY,
    override val returnTo: NpcPosition = anchor,
    override val allowPlayers: Boolean = false,
    override val tactics: CombatTactics = CombatTactics(),
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : CombatMissionDefinition {
    val route: List<NpcPosition> = java.util.List.copyOf(route)
    override val operationId: String = ID
    override fun validationProblem(): String? = commonProblem() ?: when {
        route.size !in 1..16 -> "patrol requires 1..16 waypoints"
        rounds !in 1..64 || dwellTicks !in 0..1200 -> "patrol rounds must be 1..64 and dwell 0..1200 ticks"
        route.any { !area().contains(it) } -> "every waypoint must remain within the fixed mission boundary"
        (reaction == PatrolReaction.PROTECT_SUMMONER) != (subjectUuid != null) -> "summoner protection requires exactly one bound subject UUID"
        (reaction == PatrolReaction.SUPPORT) != (supportTargetUuid != null) -> "support requires one explicitly designated enemy UUID"
        reaction == PatrolReaction.AREA && filter.isEmpty -> "area patrol requires an explicit type/tag filter"
        else -> null
    }
    fun withTactics(value: CombatTactics) = PatrolTaskDefinition(dimensionId, anchor, leash, route, rounds, dwellTicks,
        reaction, subjectUuid, supportTargetUuid, filter, returnTo, allowPlayers, value, budget, version)
    fun withBudget(value: TaskBudget) = PatrolTaskDefinition(dimensionId, anchor, leash, route, rounds, dwellTicks,
        reaction, subjectUuid, supportTargetUuid, filter, returnTo, allowPlayers, tactics, value, version)
    companion object { const val ID = "samcnpc:patrol" }
}
