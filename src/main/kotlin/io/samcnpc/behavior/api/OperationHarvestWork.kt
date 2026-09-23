package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcEntityTypeFilter

enum class OperationMiningMethod { EXPOSED, VEIN, TUNNEL, EXCAVATION }
enum class OperationMiningCounting { DELIVERED_ITEMS, REMOVED_RESOURCE_BLOCKS, CLEARED_VOLUME }
enum class OperationTunnelDirection { EAST, WEST, SOUTH, NORTH }
enum class OperationCrop { WHEAT, CARROT, POTATO }
enum class OperationFarmMode { HARVEST, REPLANT, CULTIVATE }
enum class OperationSaplingSpecies { OAK, BIRCH, DARK_OAK }
enum class OperationPlantingMode { PATCH, GAPS }
enum class OperationWorkTools { INHERIT_CORE_SETTINGS }

data class OperationTunnelGeometry(val origin: NpcBlockPosition, val direction: OperationTunnelDirection,
    val width: Int, val height: Int, val length: Int, val stepDown: Int = 0)

data class OperationMiningWork(val area: OperationWorkArea, val method: OperationMiningMethod,
    val resources: OperationResourceIds, val access: OperationResourceIds? = null,
    val tunnel: OperationTunnelGeometry? = null)

data class OperationFarmWork(
    val area: OperationWorkArea,
    val crop: OperationCrop,
    val mode: OperationFarmMode,
    val cycles: Int = 1,
    val prepareSoil: Boolean = false,
    val seedSources: OperationContainers? = null,
    val keepSeeds: Int = 0,
    val sourceKeepSeeds: Int = 0,
    val growthWaitTicks: Int = 2400,
    val growthCheckTicks: Int = 100,
)

class OperationPlantingWork(
    val area: OperationWorkArea,
    val species: OperationSaplingSpecies,
    val mode: OperationPlantingMode,
    val spacing: Int = 6,
    positions: List<NpcBlockPosition>? = null,
    val sources: OperationContainers? = null,
    val keepSaplings: Int = 0,
    val sourceKeep: Int = 0,
) {
    init { require(positions == null || positions.size in 1..128) { "explicit planting requires one to 128 bases" } }
    val positions: List<NpcBlockPosition>? = positions?.let { java.util.List.copyOf(it) }
}

sealed interface OperationFoodWork {
    data class Drops(val area: OperationWorkArea) : OperationFoodWork
    data class Berries(val area: OperationWorkArea) : OperationFoodWork
    data class Stored(val sources: OperationContainers, val sourceKeep: Int = 0) : OperationFoodWork
    data class Hunt(val area: OperationWorkArea, val targets: NpcEntityTypeFilter, val limit: Int = 8) : OperationFoodWork
}
