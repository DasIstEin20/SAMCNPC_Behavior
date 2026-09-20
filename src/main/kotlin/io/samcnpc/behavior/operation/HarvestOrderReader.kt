package io.samcnpc.behavior.operation

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*

internal object HarvestOrderReader {
    fun read(type: OperationType, p: JsonObject, dimension: String, budget: OperationBudget): OperationHarvestOrder {
        if (type == OperationType.LUMBERJACK) return OperationHarvestOrder.Lumberjack(dimension,
            OperationValueReader.area(p.obj("area")), OperationWoodSelection(p.getAsJsonArray("wood").map { it.asString }),
            OperationValueReader.block(p.obj("destination")), p.int("quantity"), OperationWorkTools.valueOf(p.text("tools")),
            budget, p.maybe("supplySources")?.let(OperationValueReader::containers), p.maybe("replant")?.let(::planting))
        val anchor = OperationValueReader.position(p.obj("anchor"))
        val travel = p.number("travelRadius")
        val back = p.maybe("returnTo")?.let(OperationValueReader::position)
        val work = p.obj("work")
        val quantity = p.int("quantity")
        return when (type) {
            OperationType.MINING -> OperationHarvestOrder.Mining(dimension, mining(work), OperationValueReader.resources(p.get("outputs")),
                OperationValueReader.containers(p.obj("destinations")), quantity, OperationMiningCounting.valueOf(p.text("counting")), anchor, travel, back, budget)
            OperationType.FARM -> OperationHarvestOrder.Farm(dimension, farm(work), OperationValueReader.containers(p.obj("destinations")),
                quantity, anchor, travel, back, budget)
            OperationType.PLANTING -> OperationHarvestOrder.Planting(dimension, plantingWork(work), quantity, anchor, travel, back, budget)
            OperationType.FOOD -> OperationHarvestOrder.Food(dimension, food(work), OperationValueReader.resources(p.get("outputs")),
                OperationValueReader.containers(p.obj("destinations")), quantity, p.int("keepFood"), anchor, travel, back, budget)
            else -> error("Nonharvest order reached harvest conversion")
        }
    }
    private fun planting(p: JsonObject) = read(OperationType.PLANTING, p, p.text("dimensionId"),
        OperationValueReader.budget(p.obj("budget"))) as OperationHarvestOrder.Planting

    private fun mining(p: JsonObject) = OperationMiningWork(OperationValueReader.area(p.obj("area")),
        OperationMiningMethod.valueOf(p.text("method")), OperationValueReader.resources(p.get("resources")),
        if (p.get("access").isJsonNull) null else OperationValueReader.resources(p.get("access")), p.maybe("tunnel")?.let { tunnel ->
            OperationTunnelGeometry(OperationValueReader.block(tunnel.obj("origin")), OperationTunnelDirection.valueOf(tunnel.text("direction")),
                tunnel.int("width"), tunnel.int("height"), tunnel.int("length"))
        })
    private fun farm(p: JsonObject) = OperationFarmWork(OperationValueReader.area(p.obj("area")), OperationCrop.valueOf(p.text("crop")),
        OperationFarmMode.valueOf(p.text("mode")), p.int("cycles"), p.flag("prepareSoil"),
        p.maybe("seedSources")?.let(OperationValueReader::containers), p.int("keepSeeds"), p.int("sourceKeepSeeds"),
        p.int("growthWaitTicks"), p.int("growthCheckTicks"))
    private fun plantingWork(p: JsonObject) = OperationPlantingWork(OperationValueReader.area(p.obj("area")),
        OperationSaplingSpecies.valueOf(p.text("species")), OperationPlantingMode.valueOf(p.text("mode")), p.int("spacing"),
        if (p.get("positions").isJsonNull) null else p.getAsJsonArray("positions").map { OperationValueReader.block(it.asJsonObject) },
        p.maybe("sources")?.let(OperationValueReader::containers), p.int("keepSaplings"), p.int("sourceKeep"))
    private fun food(p: JsonObject): OperationFoodWork = when (p.text("kind")) {
        "DROPS" -> OperationFoodWork.Drops(OperationValueReader.area(p.obj("area")))
        "BERRIES" -> OperationFoodWork.Berries(OperationValueReader.area(p.obj("area")))
        "STORED" -> OperationFoodWork.Stored(OperationValueReader.containers(p.obj("sources")), p.int("sourceKeep"))
        "HUNT" -> OperationFoodWork.Hunt(OperationValueReader.area(p.obj("area")), OperationValueReader.filter(p.obj("targets")), p.int("limit"))
        else -> error("Validated food discriminator is not mapped")
    }
}
