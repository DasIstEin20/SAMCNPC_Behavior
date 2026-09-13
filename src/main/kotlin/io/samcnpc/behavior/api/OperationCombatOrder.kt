package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcPosition
import java.util.UUID

enum class OperationWeaponPreference { CURRENT, MELEE, RANGED, AUTO }
enum class OperationWeaponAllowance { MELEE, RANGED, BOTH }
enum class OperationPatrolReaction { PASSIVE, RETALIATE, PROTECT_SUMMONER, SUPPORT, AREA }

data class OperationCombatTactics(
    val preference: OperationWeaponPreference = OperationWeaponPreference.AUTO,
    val allowed: OperationWeaponAllowance = OperationWeaponAllowance.BOTH,
    val equipArmor: Boolean = true,
    val useShield: Boolean = true,
    val heal: Boolean = true,
    val retreatAt: Double = 0.3,
    val returnAt: Double = 0.65,
    val rangedMinDistance: Double = 4.0,
    val rangedMaxDistance: Double = 14.0,
) {
    companion object {
        val HELD_MELEE = OperationCombatTactics(OperationWeaponPreference.CURRENT,
            OperationWeaponAllowance.MELEE, false, false, false, 0.0)
    }
}

/** Typed goals preserve explicit targets, hard weapon constraints and bounded pursuit. */
sealed interface OperationCombatOrder : OperationOrder {
    val anchor: NpcPosition
    val leash: Double
    val allowPlayers: Boolean
    val tactics: OperationCombatTactics

    data class Attack(
        override val dimensionId: String,
        val targetUuid: UUID,
        override val anchor: NpcPosition,
        override val leash: Double = 24.0,
        override val allowPlayers: Boolean = false,
        override val budget: OperationBudget = OperationBudget(ticks = 600),
        override val tactics: OperationCombatTactics = OperationCombatTactics.HELD_MELEE,
    ) : OperationCombatOrder { override val type: OperationType get() = OperationType.ATTACK }

    data class Defend(
        override val dimensionId: String,
        override val anchor: NpcPosition,
        override val leash: Double,
        val subjectUuid: UUID? = null,
        val filter: NpcEntityTypeFilter = NpcEntityTypeFilter.ANY,
        val dutyTicks: Int = 1200,
        val returnTo: NpcPosition = anchor,
        override val allowPlayers: Boolean = false,
        override val tactics: OperationCombatTactics = OperationCombatTactics(),
        override val budget: OperationBudget = OperationBudget(ticks = dutyTicks + 400),
    ) : OperationCombatOrder { override val type: OperationType get() = OperationType.DEFEND }

    data class AreaAttack(
        override val dimensionId: String,
        override val anchor: NpcPosition,
        override val leash: Double,
        val filter: NpcEntityTypeFilter,
        val quota: Int,
        val returnTo: NpcPosition = anchor,
        override val allowPlayers: Boolean = false,
        override val tactics: OperationCombatTactics = OperationCombatTactics(),
        override val budget: OperationBudget = OperationBudget(ticks = 2400),
    ) : OperationCombatOrder { override val type: OperationType get() = OperationType.ATTACK_AREA }

    class Patrol(
        override val dimensionId: String,
        override val anchor: NpcPosition,
        override val leash: Double,
        route: List<NpcPosition>,
        val rounds: Int = 1,
        val dwellTicks: Int = 20,
        val reaction: OperationPatrolReaction = OperationPatrolReaction.RETALIATE,
        val subjectUuid: UUID? = null,
        val supportTargetUuid: UUID? = null,
        val filter: NpcEntityTypeFilter = NpcEntityTypeFilter.ANY,
        val returnTo: NpcPosition = anchor,
        override val allowPlayers: Boolean = false,
        override val tactics: OperationCombatTactics = OperationCombatTactics(),
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationCombatOrder {
        init { require(route.size in 1..16) { "patrol requires one to sixteen waypoints" } }
        val route: List<NpcPosition> = java.util.List.copyOf(route)
        override val type: OperationType get() = OperationType.PATROL
    }
}
