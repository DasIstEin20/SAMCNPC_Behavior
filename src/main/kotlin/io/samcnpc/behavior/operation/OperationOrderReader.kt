package io.samcnpc.behavior.operation

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*

internal object OperationOrderReader {
    fun read(document: JsonObject): OperationOrder {
        val type = OperationType.entries.single { it.operationId == document.text("type") }
        val p = document.obj("parameters")
        val dimension = p.text("dimensionId")
        val budget = OperationValueReader.budget(p.obj("budget"))
        return when (type) {
            OperationType.FIELD_PREPARATION -> OperationPrepareFieldOrder(dimension,OperationValueReader.area(p.obj("area")),
                OperationValueReader.position(p.obj("anchor")),p.number("travelRadius"),p.maybe("returnTo")?.let(OperationValueReader::position),budget)
            OperationType.NAVIGATE -> OperationOrder.Navigate(dimension, OperationValueReader.position(p.obj("destination")),
                p.number("speed").toFloat(), p.number("arrivalDistance"), budget)
            OperationType.DELIVER -> OperationOrder.Deliver(dimension, OperationValueReader.block(p.obj("destination")),
                p.text("itemId"), p.int("quantity"), OperationValueReader.position(p.obj("anchor")), p.int("keepAtLeast"), budget)
            OperationType.TRANSPORT -> OperationOrder.Transport(dimension, OperationValueReader.containers(p.obj("sources")),
                OperationValueReader.containers(p.obj("destinations")), p.text("itemId"), p.int("quantity"),
                OperationValueReader.position(p.obj("anchor")), p.number("travelRadius"), p.int("keepAtLeast"),
                p.int("sourceKeepAtLeast"), p.maybe("returnTo")?.let(OperationValueReader::position), budget)
            OperationType.MACHINE -> OperationOrder.Machine(dimension,
                OperationMachineFeeds(p.getAsJsonArray("feeds").map { OperationValueReader.port(it.asJsonObject) }),
                OperationValueReader.port(p.obj("output")), OperationValueReader.position(p.obj("anchor")), p.number("travelRadius"),
                p.maybe("returnTo")?.let(OperationValueReader::position), p.int("pollTicks"), p.int("noProgressTicks"), budget)
            OperationType.FISH -> OperationOrder.Fish(dimension, OperationValueReader.block(p.obj("water")),
                OperationValueReader.position(p.obj("standing")), p.int("catches"), OperationValueReader.position(p.obj("anchor")),
                p.number("travelRadius"), p.maybe("returnTo")?.let(OperationValueReader::position), p.int("pickupWaitTicks"), budget)
            OperationType.EXPLORE -> OperationOrder.Explore(dimension, OperationValueReader.position(p.obj("anchor")),
                p.int("radius"), p.int("cellStep"), p.int("maxCells"), p.int("verticalRange"), p.int("chunkBudget"), p.int("heading"), budget)
            OperationType.INVENTORY -> OperationInventoryOrder(dimension, OperationValueReader.inventory(p.obj("work")),
                OperationValueReader.position(p.obj("anchor")), OperationValueReader.position(p.obj("returnTo")),
                p.number("travelRadius"), p.int("workTicks"), p.int("maxSteps"), budget)
            OperationType.ATTACK, OperationType.DEFEND, OperationType.ATTACK_AREA, OperationType.PATROL -> combat(type, p, dimension, budget)
            OperationType.MINING, OperationType.FARM, OperationType.PLANTING, OperationType.FOOD, OperationType.LUMBERJACK ->
                HarvestOrderReader.read(type, p, dimension, budget)
        }
    }

    private fun combat(type: OperationType, p: JsonObject, dimension: String, budget: OperationBudget): OperationOrder {
        val anchor = OperationValueReader.position(p.obj("anchor"))
        val tactics = OperationValueReader.tactics(p.obj("tactics"))
        val leash = p.number("leash")
        val players = p.flag("allowPlayers")
        if (type == OperationType.ATTACK) return OperationCombatOrder.Attack(dimension, p.uuid("targetUuid"), anchor, leash, players, budget, tactics)
        val returnTo = OperationValueReader.position(p.obj("returnTo"))
        val filter = OperationValueReader.filter(p.obj("filter"))
        return when (type) {
            OperationType.DEFEND -> OperationCombatOrder.Defend(dimension, anchor, leash, p.optionalUuid("subjectUuid"),
                filter, p.int("dutyTicks"), returnTo, players, tactics, budget)
            OperationType.ATTACK_AREA -> OperationCombatOrder.AreaAttack(dimension, anchor, leash, filter, p.int("quota"), returnTo, players, tactics, budget)
            OperationType.PATROL -> OperationCombatOrder.Patrol(dimension, anchor, leash,
                p.getAsJsonArray("route").map { OperationValueReader.position(it.asJsonObject) }, p.int("rounds"), p.int("dwellTicks"),
                OperationPatrolReaction.valueOf(p.text("reaction")), p.optionalUuid("subjectUuid"), p.optionalUuid("supportTargetUuid"),
                filter, returnTo, players, tactics, budget)
            else -> error("Noncombat order reached combat conversion")
        }
    }
}
