package io.samcnpc.behavior.task

import net.minecraft.nbt.*

internal object InventoryLedgerCodec {
    fun write(resources: HarvestResources) = CompoundTag().apply {
        putBoolean("uncertain", resources.uncertain)
        put("items", ListTag().apply { for ((id, value) in resources.entries.toSortedMap()) add(CompoundTag().apply {
            putString("item", id); putInt("initial", value.initial); putInt("gathered", value.gathered); putInt("supplied", value.supplied)
            putInt("consumed", value.consumed); putInt("delivered", value.delivered); putInt("lost", value.lost); putInt("retained", value.retained)
        }) })
    }
    fun read(tag: CompoundTag): HarvestResources {
        InventoryWorkCodec.keys(tag, "uncertain", "items")
        val entries = linkedMapOf<String, HarvestResource>()
        for (entry in InventoryWorkCodec.list(tag, "items", HarvestResources.MAX_KINDS)) {
            InventoryWorkCodec.keys(entry, "item", "initial", "gathered", "supplied", "consumed", "delivered", "lost", "retained")
            val id = entry.getString("item")
            val values = listOf("initial", "gathered", "supplied", "consumed", "delivered", "lost", "retained").map { InventoryWorkCodec.int(entry, it) }
            require(entries.put(id, HarvestResource(values[0], values[1], values[2], values[3], values[4], values[5], values[6])) == null) { "duplicate inventory resource" }
        }
        return HarvestResources.restore(entries, InventoryWorkCodec.bool(tag, "uncertain"))
    }
}
