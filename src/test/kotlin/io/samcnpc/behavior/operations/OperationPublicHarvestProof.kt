package io.samcnpc.behavior.operations

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*

/** Independent fixture conversion preserves each original definition before the public gateway executes it. */
internal object OperationPublicHarvestProof {
    fun order(d: TaskDefinition): OperationHarvestOrder? = when (d) {
        is MiningTaskDefinition -> OperationHarvestOrder.Mining(d.dimensionId,
            OperationMiningWork(area(d.work.area), OperationMiningMethod.valueOf(d.work.method.name), ids(d.work.resources),
                d.work.access?.let(::ids), d.work.tunnel?.let { OperationTunnelGeometry(it.origin,
                    OperationTunnelDirection.valueOf(it.direction.name), it.width, it.height, it.length, it.stepDown) }),
            ids(d.outputs), containers(d.destinations), d.quantity, OperationMiningCounting.valueOf(d.counting.name),
            d.anchor, d.travelRadius, d.returnTo, budget(d.budget))
        is FarmTaskDefinition -> OperationHarvestOrder.Farm(d.dimensionId,
            OperationFarmWork(area(d.work.area), OperationCrop.valueOf(d.work.crop.name), OperationFarmMode.valueOf(d.work.mode.name),
                d.work.cycles, d.work.prepareSoil, d.work.seedSources?.let(::containers), d.work.keepSeeds,
                d.work.sourceKeepSeeds, d.work.growthWaitTicks, d.work.growthCheckTicks),
            containers(d.destinations), d.quantity, d.anchor, d.travelRadius, d.returnTo, budget(d.budget))
        is PlantingTaskDefinition -> planting(d)
        is FoodTaskDefinition -> OperationHarvestOrder.Food(d.dimensionId, when (val work = d.work) {
            is FoodWorkOrder.Drops -> OperationFoodWork.Drops(area(work.area))
            is FoodWorkOrder.Berries -> OperationFoodWork.Berries(area(work.area))
            is FoodWorkOrder.Stored -> OperationFoodWork.Stored(containers(work.sources), work.sourceKeep)
            is FoodWorkOrder.Hunt -> OperationFoodWork.Hunt(area(work.area), work.targets, work.limit)
        }, ids(d.outputs), containers(d.destinations), d.quantity, d.keepFood, d.anchor, d.travelRadius, d.returnTo, budget(d.budget))
        is LumberjackTaskDefinition -> if (d.version != 2) null else OperationHarvestOrder.Lumberjack(d.dimensionId, area(d.area),
            OperationWoodSelection(d.wood.selectors.sorted()), d.destination, d.quantity, OperationWorkTools.valueOf(d.tools.name),
            budget(d.budget), d.supplySources?.let(::containers), d.replant?.let(::planting))
        else -> null
    }
    private fun planting(d: PlantingTaskDefinition) = OperationHarvestOrder.Planting(d.dimensionId,
        OperationPlantingWork(area(d.work.area), OperationSaplingSpecies.valueOf(d.work.species.name),
            OperationPlantingMode.valueOf(d.work.mode.name), d.work.spacing, d.work.positions,
            d.work.sources?.let(::containers), d.work.keepSaplings, d.work.sourceKeep),
        d.quantity, d.anchor, d.travelRadius, d.returnTo, budget(d.budget))
    private fun box(value: WorkBox) = OperationWorkBox(value.min, value.max)
    private fun area(value: WorkArea) = OperationWorkArea(box(value.bounds), value.exclusions.map(::box))
    private fun ids(value: WorkResourceIds) = OperationResourceIds(value.values)
    private fun budget(value: TaskBudget) = OperationBudget(value.ticks, value.attempts, value.backoffTicks)
    private fun containers(value: ContainerChoices) = OperationContainers(value.positions,
        OperationContainerPreference.valueOf(value.preference.name))
}
