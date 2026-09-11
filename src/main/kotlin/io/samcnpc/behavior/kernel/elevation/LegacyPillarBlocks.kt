package io.samcnpc.behavior.kernel.elevation

import io.samcnpc.core.api.NpcBlockPosition

/** v0..18 used one material per pillar. Only these vanilla item/block mappings are known. */
internal object LegacyPillarBlocks {
    private val identicalIds = buildSet {
        for (path in listOf("stone", "cobblestone", "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium",
            "moss_block", "netherrack", "deepslate", "cobbled_deepslate", "andesite", "diorite", "granite", "blackstone")) add("minecraft:$path")
        for (wood in listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            for (part in listOf("log", "wood", "planks")) add("minecraft:${wood}_$part")
            for (part in listOf("log", "wood")) add("minecraft:stripped_${wood}_$part")
        }
        for (wood in listOf("crimson", "warped")) {
            for (part in listOf("stem", "hyphae", "planks")) add("minecraft:${wood}_$part")
            for (part in listOf("stem", "hyphae")) add("minecraft:stripped_${wood}_$part")
        }
    }
    fun expectedIds(positions: List<NpcBlockPosition>, itemId: String): MutableMap<NpcBlockPosition, String> {
        val result = linkedMapOf<NpcBlockPosition, String>()
        if (itemId in identicalIds) for (position in positions) result[position] = itemId
        return result
    }
}
