package io.samcnpc.behavior.mission

import io.samcnpc.behavior.api.ItemQuery
import io.samcnpc.behavior.api.OperationOrder
import io.samcnpc.core.api.*

internal sealed interface MissionPredicate {
    data class Inventory(val query: ItemQuery, val count: Int, val durability: Double) : MissionPredicate
    data class Equipment(val query: ItemQuery, val destination: NpcEquipmentDestination, val durability: Double) : MissionPredicate
    data class Arrival(val position: NpcPosition, val radius: Double) : MissionPredicate
    data class Stock(val query: NpcStockQuery, val count: Int) : MissionPredicate
    data class Soil(val cells: List<NpcBlockPosition>) : MissionPredicate
    data class TaskSuccess(val stageId: String) : MissionPredicate
}

/** Current-state requirements are always reobserved, including at final completion. */
internal data class MissionRequirement(val id: String, val predicate: MissionPredicate)
internal data class MissionStage(
    val id: String, val pack: String, val order: OperationOrder?,
    val completion: List<String>, val timeoutTicks: Int, val success: String?,
)
internal data class MissionDefinition(
    val id: String, val dimension: String, val body: String, val hash: String,
    val requirements: List<MissionRequirement>, val stages: List<MissionStage>, val guards: List<String>,
)
