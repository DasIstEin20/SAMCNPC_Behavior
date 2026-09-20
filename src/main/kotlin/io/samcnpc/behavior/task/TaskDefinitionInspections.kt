package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationDefinitionSnapshot
import io.samcnpc.behavior.api.OperationValue
import io.samcnpc.behavior.task.TaskInspectionValues.v
import io.samcnpc.behavior.task.TaskInspectionValues.record
import io.samcnpc.behavior.task.TaskInspectionValues.sequence
import io.samcnpc.behavior.task.TaskInspectionValues.strings
import io.samcnpc.behavior.task.TaskInspectionValues.containers
import io.samcnpc.behavior.task.TaskInspectionValues.port
import io.samcnpc.behavior.task.TaskInspectionValues.travel
import io.samcnpc.behavior.task.TaskInspectionValues.merged

/** Explicit projection of validated definitions. It never reconstructs defaults or parses JSON. */
internal object TaskDefinitionInspections {
    fun capture(d: TaskDefinition) = OperationDefinitionSnapshot(d.operationId, d.version, parameters(d))

    fun parameters(d: TaskDefinition): OperationValue.Record {
        val common = record("dimensionId" to v(d.dimensionId), "budget" to record(
            "ticks" to v(d.budget.ticks), "attempts" to v(d.budget.attempts), "backoffTicks" to v(d.budget.backoffTicks)))
        val fields = when (d) {
            is NavigateTaskDefinition -> record("destination" to v(d.destination), "speed" to v(d.speed), "arrivalDistance" to v(d.arrivalDistance))
            is DeliveryTaskDefinition -> record("destination" to v(d.destination), "itemId" to v(d.itemId),
                "quantity" to v(d.quantity), "anchor" to v(d.anchor), "keepAtLeast" to v(d.keepAtLeast))
            is TransportTaskDefinition -> merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
                "sources" to containers(d.sources), "destinations" to containers(d.destinations), "itemId" to v(d.itemId),
                "quantity" to v(d.quantity), "keepAtLeast" to v(d.keepAtLeast), "sourceKeepAtLeast" to v(d.sourceKeepAtLeast)))
            is MachineTaskDefinition -> merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
                "feeds" to sequence(d.feeds.ports.map(::port)), "output" to port(d.output),
                "pollTicks" to v(d.pollTicks), "noProgressTicks" to v(d.noProgressTicks)))
            is FishingTaskDefinition -> merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
                "water" to v(d.water), "standing" to v(d.standing), "catches" to v(d.catches), "pickupWaitTicks" to v(d.pickupWaitTicks)))
            is ExplorerTaskDefinition -> record("anchor" to v(d.anchor), "radius" to v(d.radius), "cellStep" to v(d.cellStep),
                "maxCells" to v(d.maxCells), "verticalRange" to v(d.verticalRange), "chunkBudget" to v(d.chunkBudget), "heading" to v(d.heading))
            is InventoryTaskDefinition -> merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
                "work" to inventory(d.work), "workTicks" to v(d.workTicks), "maxSteps" to v(d.maxSteps)))
            is AttackTaskDefinition -> TaskCombatInspections.attack(d)
            is CombatMissionDefinition -> TaskCombatInspections.mission(d)
            is MiningTaskDefinition -> TaskHarvestInspections.mining(d)
            is FarmTaskDefinition -> TaskHarvestInspections.farm(d)
            is PlantingTaskDefinition -> TaskHarvestInspections.planting(d)
            is FoodTaskDefinition -> TaskHarvestInspections.food(d)
            is LumberjackTaskDefinition -> TaskHarvestInspections.lumberjack(d)
        }
        return merged(common, fields)
    }

    private fun inventory(work: InventoryWork): OperationValue.Record = when (work) {
        is SupplyStock -> record("kind" to v(work.kind.name), "sources" to containers(work.containers),
            "needs" to sequence(work.needs.map { record("itemId" to v(it.itemId), "minimum" to v(it.minimum),
                "target" to v(it.target), "sourceReserve" to v(it.sourceReserve)) }))
        is UnloadExcess -> record("kind" to v(work.kind.name), "destinations" to containers(work.containers),
            "reserves" to sequence(work.reserves.map { record("itemId" to v(it.itemId), "keep" to v(it.keep)) }))
        is PickupNearby -> record("kind" to v(work.kind.name), "itemIds" to strings(work.itemIds),
            "radius" to v(work.radius), "maxItems" to v(work.maxItems))
    }
}
