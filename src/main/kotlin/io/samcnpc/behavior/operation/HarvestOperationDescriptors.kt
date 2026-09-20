package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

internal object HarvestOperationDescriptors {
    private val harvestChanges = listOf("QUANTITY", "RECIPIENTS", "EXTEND_TIME", "REPLACE", "TACTICS", "REACTION", "LOGISTICS")
    private val diameter = relation("HARVEST_DIAMETER", "All considered work, supply, recipient and return points fit the travel boundary and are pairwise within 58 blocks.", "work", "destinations", "anchor", "returnTo", "travelRadius")
    fun values(): List<OperationDescriptor> = listOf(
        descriptor(OperationType.MINING, "Mine only the authorized resources/access geometry and deliver outputs.", "MINING_FINISHED: counting selects delivered items, removed resource blocks or one fully cleared supplied geometry.",
            common() + travel() + listOf(required("work", ref("miningWork")), required("outputs", ref("resources")),
                required("destinations", ref("containers")), required("quantity", integer(1, 2304, "counting_basis")),
                required("counting", choice(*OperationMiningCounting.entries.map { it.name }.toTypedArray()))),
            harvestChanges, diameter,
            relation("MINING_COUNTING", "CLEARED_VOLUME requires TUNNEL/EXCAVATION and quantity=1. Recipients cannot occupy the permitted removal area. Ore blocks, their drops and ingots are distinct resources.", "counting", "quantity", "work", "outputs", "destinations")),
        descriptor(OperationType.FARM, "Harvest or cultivate one explicit finite crop field with optional seed sources.", "FARM_FINISHED: delivered crop item count, not seed count or attempted harvests.",
            common(optional("budget", ref("farmBudget"), "{}")) + travel() + listOf(required("work", ref("farmWork")),
                required("destinations", ref("containers")), required("quantity", integer(1, 2304, "items"))),
            harvestChanges + "SOURCES", diameter,
            relation("FARM_RECIPIENT", "Recipients cannot occupy the permitted field.", "destinations", "work.area")),
        planting(),
        descriptor(OperationType.FOOD, "Acquire food by the explicit drops, berries, storage or hunting method.", "FOOD_FINISHED: delivered matching food with an independent retained ration.",
            common() + travel() + listOf(required("work", ref("foodWork")), required("outputs", ref("resources")),
                required("destinations", ref("containers")), required("quantity", integer(1, 2304, "items")), required("keepFood", integer(0, 64, "items"))),
            harvestChanges + "SOURCES", diameter,
            relation("FOOD_METHOD", "Scan volume <=32768. BERRIES outputs include minecraft:sweet_berries. HUNT requires nonempty nonplayer targets (literal minecraft:player forbidden). STORED sources and recipients are disjoint. SOURCES amendment only supports STORED.", "work", "outputs", "destinations")),
        descriptor(OperationType.LUMBERJACK, "Harvest supported wood in an explicit area and deliver it; optional supplies and gap replanting are separate permissions.", "DELIVERED: confirmed matching wood, including the bounded optional replant contract.",
            common() + listOf(required("area", ref("area")), required("wood", ref("wood")), required("destination", ref("block")),
                required("quantity", integer(1, 2304, "items")), optional("tools", choice("INHERIT_CORE_SETTINGS"), "\"INHERIT_CORE_SETTINGS\""),
                nullable("supplySources", ref("containers")), nullable("replant", ref("plantingParameters"))),
            harvestChanges + "SOURCES",
            relation("WOOD_SUPPLIES", "Every supplied source is within 60 blocks of output. No implicit withdrawals from output in v2.", "supplySources", "destination"),
            relation("WOOD_REPLANT", "Replant is same-dimension GAPS; plane and every footprint lie inside permitted wood cells, and output fits replant travel bounds.", "replant", "dimensionId", "area", "destination")),
    )

    fun planting(): OperationDescriptor = descriptor(OperationType.PLANTING, "Plant supplied permitted sites or fill gaps, using real saplings.", "PLANTING_FINISHED: completed species layouts (DARK_OAK uses a 2x2 layout), not individual sapling count.",
        common() + travel() + listOf(required("work", ref("plantingWork")), required("quantity", integer(1, 128, "layouts"))),
        listOf("QUANTITY", "SOURCES", "EXTEND_TIME", "REPLACE", "TACTICS", "REACTION", "LOGISTICS"), diameter)
}
