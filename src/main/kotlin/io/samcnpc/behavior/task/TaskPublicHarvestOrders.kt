package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*

/** Converts finite public data to existing harvest validators/executors without new policy. */
internal object TaskPublicHarvestOrders {
    fun definition(order: OperationHarvestOrder, budget: TaskBudget): TaskDefinition {
        val version = order.type.definitionVersion
        return when (order) {
            is OperationHarvestOrder.Mining -> MiningTaskDefinition(order.dimensionId, mining(order.work),
                ids(order.outputs), TaskPublicOrders.containers(order.destinations), order.quantity,
                when (order.counting) {
                    OperationMiningCounting.DELIVERED_ITEMS -> MiningCounting.DELIVERED_ITEMS
                    OperationMiningCounting.REMOVED_RESOURCE_BLOCKS -> MiningCounting.REMOVED_RESOURCE_BLOCKS
                    OperationMiningCounting.CLEARED_VOLUME -> MiningCounting.CLEARED_VOLUME
                }, order.anchor, order.travelRadius, order.returnTo, budget, version)
            is OperationHarvestOrder.Farm -> FarmTaskDefinition(order.dimensionId, farm(order.work),
                TaskPublicOrders.containers(order.destinations), order.quantity, order.anchor,
                order.travelRadius, order.returnTo, budget, version)
            is OperationHarvestOrder.Planting -> planting(order, budget)
            is OperationHarvestOrder.Food -> FoodTaskDefinition(order.dimensionId, food(order.work),
                ids(order.outputs), TaskPublicOrders.containers(order.destinations), order.quantity,
                order.keepFood, order.anchor, order.travelRadius, order.returnTo, budget, version)
            is OperationHarvestOrder.Lumberjack -> LumberjackTaskDefinition(order.dimensionId, area(order.area),
                WoodSelection(order.wood.selectors), order.destination, order.quantity, when (order.tools) {
                    OperationWorkTools.INHERIT_CORE_SETTINGS -> WorkToolPolicy.INHERIT_CORE_SETTINGS
                }, budget, version, order.supplySources?.let(TaskPublicOrders::containers),
                order.replant?.let { planting(it, TaskBudget(it.budget.ticks, it.budget.attempts, it.budget.backoffTicks)) })
        }
    }
    private fun ids(value: OperationResourceIds) = WorkResourceIds(value.values)
    private fun box(value: OperationWorkBox) = WorkBox(value.min, value.max)
    private fun area(value: OperationWorkArea) = WorkArea(box(value.bounds), value.exclusions.map(::box))
    private fun mining(value: OperationMiningWork) = MiningWorkOrder(area(value.area), when (value.method) {
        OperationMiningMethod.EXPOSED -> MiningMethod.EXPOSED
        OperationMiningMethod.VEIN -> MiningMethod.VEIN
        OperationMiningMethod.TUNNEL -> MiningMethod.TUNNEL
        OperationMiningMethod.EXCAVATION -> MiningMethod.EXCAVATION
    }, ids(value.resources), value.access?.let(::ids), value.tunnel?.let { tunnel ->
        TunnelGeometry(tunnel.origin, when (tunnel.direction) {
            OperationTunnelDirection.EAST -> TunnelDirection.EAST
            OperationTunnelDirection.WEST -> TunnelDirection.WEST
            OperationTunnelDirection.SOUTH -> TunnelDirection.SOUTH
            OperationTunnelDirection.NORTH -> TunnelDirection.NORTH
        }, tunnel.width, tunnel.height, tunnel.length)
    })
    private fun farm(value: OperationFarmWork) = FarmWorkOrder(area(value.area), when (value.crop) {
        OperationCrop.WHEAT -> FarmCrop.WHEAT
        OperationCrop.CARROT -> FarmCrop.CARROT
        OperationCrop.POTATO -> FarmCrop.POTATO
    }, when (value.mode) {
        OperationFarmMode.HARVEST -> FarmMode.HARVEST
        OperationFarmMode.REPLANT -> FarmMode.REPLANT
        OperationFarmMode.CULTIVATE -> FarmMode.CULTIVATE
    }, value.cycles, value.prepareSoil, value.seedSources?.let(TaskPublicOrders::containers),
        value.keepSeeds, value.sourceKeepSeeds, value.growthWaitTicks, value.growthCheckTicks)

    private fun planting(order: OperationHarvestOrder.Planting, budget: TaskBudget): PlantingTaskDefinition {
        val value = order.work
        val work = PlantingWorkOrder(area(value.area), when (value.species) {
            OperationSaplingSpecies.OAK -> SaplingSpecies.OAK
            OperationSaplingSpecies.BIRCH -> SaplingSpecies.BIRCH
            OperationSaplingSpecies.DARK_OAK -> SaplingSpecies.DARK_OAK
        }, when (value.mode) {
            OperationPlantingMode.PATCH -> PlantingMode.PATCH
            OperationPlantingMode.GAPS -> PlantingMode.GAPS
        }, value.spacing, value.positions, value.sources?.let(TaskPublicOrders::containers), value.keepSaplings, value.sourceKeep)
        return PlantingTaskDefinition(order.dimensionId, work, order.quantity, order.anchor,
            order.travelRadius, order.returnTo, budget, order.type.definitionVersion)
    }
    private fun food(value: OperationFoodWork): FoodWorkOrder = when (value) {
        is OperationFoodWork.Drops -> FoodWorkOrder.Drops(area(value.area))
        is OperationFoodWork.Berries -> FoodWorkOrder.Berries(area(value.area))
        is OperationFoodWork.Stored -> FoodWorkOrder.Stored(TaskPublicOrders.containers(value.sources), value.sourceKeep)
        is OperationFoodWork.Hunt -> FoodWorkOrder.Hunt(area(value.area), value.targets, value.limit)
    }
}
