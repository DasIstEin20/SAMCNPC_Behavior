package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

/** Origin counters cannot exceed the corresponding physical collection/transfer history. */
internal object ProducedResourcesCodec {
    fun write(resources: ProducedResources): CompoundTag {
        val tag=CompoundTag(); tag.put("physical",InventoryLedgerCodec.write(resources.physical))
        val entries=ListTag()
        for ((id, row) in resources.entries.toSortedMap()) {
            val entry=CompoundTag(); entry.putString("item",id)
            entry.putInt("stock",row.stock); entry.putInt("output",row.output)
            entry.putInt("protectedGathered",row.protectedGathered); entry.putInt("sourceOutput",row.sourceOutput); entry.putInt("deliveredOutput",row.deliveredOutput)
            entries.add(entry)
        }
        tag.put("origins",entries); return tag
    }
    fun read(tag: CompoundTag): ProducedResources {
        InventoryWorkCodec.keys(tag,"physical","origins")
        val physical=InventoryLedgerCodec.read(InventoryWorkCodec.compound(tag,"physical"))
        val origins=linkedMapOf<String,ProducedResourceOrigin>()
        for (entry in InventoryWorkCodec.list(tag,"origins",HarvestResources.MAX_KINDS)) {
            require(entry.allKeys == setOf("item","stock","output","sourceOutput","deliveredOutput") + (if (entry.contains("protectedGathered")) setOf("protectedGathered") else emptySet())) { "unknown/missing resource origin fields" }
            val id=entry.getString("item")
            val row=ProducedResourceOrigin(InventoryWorkCodec.int(entry,"stock"),InventoryWorkCodec.int(entry,"output"),
                InventoryWorkCodec.int(entry,"sourceOutput"),InventoryWorkCodec.int(entry,"deliveredOutput"),
                if (entry.contains("protectedGathered")) InventoryWorkCodec.int(entry,"protectedGathered") else 0)
            require(origins.put(id,row) == null) { "duplicate produced-resource origin" }
        }
        return ProducedResources.restore(physical,origins)
    }
}
