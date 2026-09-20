package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationValue
import io.samcnpc.behavior.task.TaskInspectionValues.v
import io.samcnpc.behavior.task.TaskInspectionValues.record
import io.samcnpc.behavior.task.TaskInspectionValues.sequence
import io.samcnpc.behavior.task.TaskInspectionValues.strings
import io.samcnpc.behavior.task.TaskInspectionValues.area
import io.samcnpc.behavior.task.TaskInspectionValues.containers
import io.samcnpc.behavior.task.TaskInspectionValues.filter
import io.samcnpc.behavior.task.TaskInspectionValues.travel
import io.samcnpc.behavior.task.TaskInspectionValues.merged

internal object TaskHarvestInspections {
    fun mining(d: MiningTaskDefinition) = merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
        "work" to miningWork(d.work), "outputs" to strings(d.outputs.values), "destinations" to containers(d.destinations),
        "quantity" to v(d.quantity), "counting" to v(d.counting.name)))

    fun farm(d: FarmTaskDefinition) = merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
        "work" to farmWork(d.work), "destinations" to containers(d.destinations), "quantity" to v(d.quantity)))

    fun planting(d: PlantingTaskDefinition) = merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
        "work" to plantingWork(d.work), "quantity" to v(d.quantity)))

    fun food(d: FoodTaskDefinition) = merged(travel(d.anchor, d.travelRadius, d.returnTo), record(
        "work" to foodWork(d.work), "outputs" to strings(d.outputs.values), "destinations" to containers(d.destinations),
        "quantity" to v(d.quantity), "keepFood" to v(d.keepFood)))

    fun lumberjack(d: LumberjackTaskDefinition) = record("area" to area(d.area), "wood" to strings(d.wood.selectors),
        "destination" to v(d.destination), "quantity" to v(d.quantity), "tools" to v(d.tools.name),
        "supplySources" to containers(d.supplySources),
        "replant" to (d.replant?.let(TaskDefinitionInspections::parameters) ?: OperationValue.Absent))

    private fun miningWork(w: MiningWorkOrder) = record("area" to area(w.area), "method" to v(w.method.name),
        "resources" to strings(w.resources.values), "access" to (w.access?.let { strings(it.values) } ?: OperationValue.Absent),
        "tunnel" to (w.tunnel?.let { record("origin" to v(it.origin), "direction" to v(it.direction.name),
            "width" to v(it.width), "height" to v(it.height), "length" to v(it.length)) } ?: OperationValue.Absent))

    private fun farmWork(w: FarmWorkOrder) = record("area" to area(w.area), "crop" to v(w.crop.name), "mode" to v(w.mode.name),
        "cycles" to v(w.cycles), "prepareSoil" to v(w.prepareSoil), "seedSources" to containers(w.seedSources),
        "keepSeeds" to v(w.keepSeeds), "sourceKeepSeeds" to v(w.sourceKeepSeeds), "growthWaitTicks" to v(w.growthWaitTicks),
        "growthCheckTicks" to v(w.growthCheckTicks))

    private fun plantingWork(w: PlantingWorkOrder) = record("area" to area(w.area), "species" to v(w.species.name),
        "mode" to v(w.mode.name), "spacing" to v(w.spacing),
        "positions" to (w.positions?.let { sequence(it.map(::v)) } ?: OperationValue.Absent),
        "sources" to containers(w.sources), "keepSaplings" to v(w.keepSaplings), "sourceKeep" to v(w.sourceKeep))

    private fun foodWork(w: FoodWorkOrder): OperationValue.Record = when (w) {
        is FoodWorkOrder.Drops -> record("kind" to v("DROPS"), "area" to area(w.area))
        is FoodWorkOrder.Berries -> record("kind" to v("BERRIES"), "area" to area(w.area))
        is FoodWorkOrder.Stored -> record("kind" to v("STORED"), "sources" to containers(w.sources), "sourceKeep" to v(w.sourceKeep))
        is FoodWorkOrder.Hunt -> record("kind" to v("HUNT"), "area" to area(w.area), "targets" to filter(w.targets), "limit" to v(w.limit))
    }
}
