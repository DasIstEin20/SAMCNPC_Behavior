package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition

/** Explicit resource goals and spatial permissions; no implicit crafting or resource generation. */
sealed interface OperationHarvestOrder : OperationOrder {
    data class Mining(
        override val dimensionId: String,
        val work: OperationMiningWork,
        val outputs: OperationResourceIds,
        val destinations: OperationContainers,
        val quantity: Int,
        val counting: OperationMiningCounting,
        val anchor: NpcPosition,
        val travelRadius: Double = 64.0,
        val returnTo: NpcPosition? = null,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationHarvestOrder { override val type: OperationType get() = OperationType.MINING }

    data class Farm(
        override val dimensionId: String,
        val work: OperationFarmWork,
        val destinations: OperationContainers,
        val quantity: Int,
        val anchor: NpcPosition,
        val travelRadius: Double = 64.0,
        val returnTo: NpcPosition? = null,
        override val budget: OperationBudget = OperationBudget(ticks = 12000),
    ) : OperationHarvestOrder { override val type: OperationType get() = OperationType.FARM }

    data class Planting(
        override val dimensionId: String,
        val work: OperationPlantingWork,
        val quantity: Int,
        val anchor: NpcPosition,
        val travelRadius: Double = 64.0,
        val returnTo: NpcPosition? = null,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationHarvestOrder { override val type: OperationType get() = OperationType.PLANTING }

    data class Food(
        override val dimensionId: String,
        val work: OperationFoodWork,
        val outputs: OperationResourceIds,
        val destinations: OperationContainers,
        val quantity: Int,
        val keepFood: Int,
        val anchor: NpcPosition,
        val travelRadius: Double = 64.0,
        val returnTo: NpcPosition? = null,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationHarvestOrder { override val type: OperationType get() = OperationType.FOOD }

    data class Lumberjack(
        override val dimensionId: String,
        val area: OperationWorkArea,
        val wood: OperationWoodSelection,
        val destination: NpcBlockPosition,
        val quantity: Int,
        val tools: OperationWorkTools = OperationWorkTools.INHERIT_CORE_SETTINGS,
        override val budget: OperationBudget = OperationBudget(),
        val supplySources: OperationContainers? = null,
        val replant: Planting? = null,
    ) : OperationHarvestOrder { override val type: OperationType get() = OperationType.LUMBERJACK }
}
