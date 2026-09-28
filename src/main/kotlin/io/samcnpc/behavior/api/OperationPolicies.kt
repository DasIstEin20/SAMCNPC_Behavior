package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcPosition
import java.util.UUID

enum class OperationObjectiveMode { PRESERVE, NEW_OBJECTIVE }
enum class OperationReactionMode { PASSIVE, RETALIATE, PROTECT_SUMMONER, PROTECT_UNIT, AREA }

data class OperationReactionPolicy(
    val mode: OperationReactionMode = OperationReactionMode.PASSIVE,
    val leash: Double = 24.0,
    val durationTicks: Int = 600,
    val cooldownTicks: Int = 40,
    val allowPlayers: Boolean = false,
    val tactics: OperationCombatTactics = OperationCombatTactics.HELD_MELEE,
    val anchor: NpcPosition? = null,
    val subjectUuid: UUID? = null,
    val filter: NpcEntityTypeFilter = NpcEntityTypeFilter.ANY,
)

/** No side work is enabled unless a corresponding finite inventory request is supplied. */
data class OperationLogisticsPolicy(
    val anchor: NpcPosition? = null,
    val supply: OperationInventoryWork.Supply? = null,
    val unload: OperationInventoryWork.Unload? = null,
    val pickup: OperationInventoryWork.Pickup? = null,
    val travelRadius: Double = 16.0,
    val workTicks: Int = 600,
    val durationTicks: Int = 1200,
    val cooldownTicks: Int = 200,
    val maxSteps: Int = 128,
    val preparation: OperationInventoryWork.Ensure? = null,
)
