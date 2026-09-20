package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

internal object HarvestOperationShapes {
    fun values(): Map<String, OperationInput> = linkedMapOf(
        "tunnel" to record(required("origin", ref("block")), required("direction", choice(*OperationTunnelDirection.entries.map { it.name }.toTypedArray())),
            required("width", integer(1, 3, "blocks")), required("height", integer(2, 4, "blocks")), required("length", integer(1, 32, "blocks"))),
        "miningWork" to record(listOf(required("area", ref("area")), required("method", choice(*OperationMiningMethod.entries.map { it.name }.toTypedArray())),
            required("resources", ref("resources")), nullable("access", ref("resources")), nullable("tunnel", ref("tunnel"))),
            relation("MINING_GEOMETRY", "Scan volume <=32768. TUNNEL requires exact tunnel bounds without exclusions. Other methods forbid tunnel. EXPOSED/VEIN forbid access. TUNNEL/EXCAVATION volume <=4096.", "area", "method", "access", "tunnel")),
        "farmWork" to record(listOf(required("area", ref("area")), required("crop", choice(*OperationCrop.entries.map { it.name }.toTypedArray())),
            required("mode", choice(*OperationFarmMode.entries.map { it.name }.toTypedArray())), optional("cycles", integer(1, 8, "cycles"), "1"),
            optional("prepareSoil", OperationInput.Flag, "false"), nullable("seedSources", ref("containers")),
            optional("keepSeeds", integer(0, 512, "items"), "0"), optional("sourceKeepSeeds", integer(0, 2304, "items"), "0"),
            optional("growthWaitTicks", integer(20, 36000, "ticks"), "2400"), optional("growthCheckTicks", integer(20, 200, "ticks"), "100")),
            relation("FARM_FIELD", "One crop-height plane with 1..512 cells and at least one permitted cell. Sources cannot occupy the field.", "area", "seedSources"),
            relation("FARM_MODE", "HARVEST requires cycles=1, prepareSoil=false, seedSources=null and keepSeeds=0. growthCheckTicks <= growthWaitTicks.", "mode", "cycles", "prepareSoil", "seedSources", "keepSeeds", "growthCheckTicks", "growthWaitTicks")),
        "plantingWork" to record(listOf(required("area", ref("area")), required("species", choice(*OperationSaplingSpecies.entries.map { it.name }.toTypedArray())),
            required("mode", choice(*OperationPlantingMode.entries.map { it.name }.toTypedArray())), optional("spacing", integer(3, 16, "blocks"), "6"),
            nullable("positions", list(ref("block"), 1, 128, true)), nullable("sources", ref("containers")),
            optional("keepSaplings", integer(0, 512, "items"), "0"), optional("sourceKeep", integer(0, 2304, "items"), "0")),
            relation("PLANTING_LAYOUT", "One-height plane; spacing >= layout size+2 (DARK_OAK=2, others=1). Explicit bases are separated in Chebyshev x/z distance by spacing; every footprint is permitted. Sources cannot occupy the plane. Resolved sites 1..128.", "area", "species", "spacing", "positions", "sources")),
        "foodDrops" to record(required("kind", choice("DROPS")), required("area", ref("area"))),
        "foodBerries" to record(required("kind", choice("BERRIES")), required("area", ref("area"))),
        "foodStored" to record(required("kind", choice("STORED")), required("sources", ref("containers")), optional("sourceKeep", integer(0, 2304, "items"), "0")),
        "foodHunt" to record(required("kind", choice("HUNT")), required("area", ref("area")), required("targets", ref("filter")),
            optional("limit", integer(1, 16, "attempts"), "8")),
        "foodWork" to OperationInput.Alternatives(listOf("foodDrops", "foodBerries", "foodStored", "foodHunt")),
        "wood" to list(choice("samcnpc:oak", "samcnpc:birch", "samcnpc:dark_oak", "samcnpc:oak_and_birch",
            "minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood",
            "minecraft:birch_log", "minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood",
            "minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood"), 1, 16),
    )
}
