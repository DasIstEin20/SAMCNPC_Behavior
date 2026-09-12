package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.MiningOrderCodec.block
import io.samcnpc.behavior.task.MiningOrderCodec.readBlock

internal object ProducedCargoCodec {
    fun write(s: ProducedCargoState,t: CompoundTag) {
        t.put("resources",ProducedResourcesCodec.write(s.resources)); t.put("deliveries",TaskObjectiveCodec.deliveries(s.deliveries))
        t.put("checkpoints",checkpoints(s.checkpoints)); s.selectedContainer?.let { t.put("container",block(it)) }
    }
    fun readInto(t: CompoundTag,s: ProducedCargoState) {
        if (t.contains("container")) s.selectedContainer=readBlock(compound(t,"container"))
        s.checkpoints.putAll(readCheckpoints(t,"checkpoints")); s.deliveries.putAll(TaskObjectiveCodec.readDeliveries(t))
        require(s.deliveries.keys.all { it in s.checkpoints }) { "delivery lacks observed recipient identity" }
        val entries=s.resources.physical.entries
        require((entries.keys+s.deliveries.values.flatMap { it.keys }).all { id -> s.deliveries.values.sumOf { (it[id] ?: 0).toLong() } == (entries[id]?.delivered ?: 0).toLong() }) { "recipient history differs from physical transfers" }
    }
    fun checkpoints(values: Map<NpcBlockPosition,ContainerCheckpoint>) = ListTag().apply {
        for ((p,c) in values) add(CompoundTag().apply { put("position",block(p)); putString("block",c.blockId); putInt("size",c.size) })
    }
    fun readCheckpoints(t: CompoundTag,key: String): Map<NpcBlockPosition,ContainerCheckpoint> {
        val values=linkedMapOf<NpcBlockPosition,ContainerCheckpoint>()
        for (v in InventoryWorkCodec.list(t,key,32)) {
            InventoryWorkCodec.keys(v,"position","block","size")
            val p=readBlock(compound(v,"position")); val c=ContainerCheckpoint(v.getString("block"),int(v,"size"))
            require(c.size in 1..64 && HarvestResources.validCounts(mapOf(c.blockId to 1)) && values.put(p,c) == null) { "invalid container identity history" }
        }
        return values
    }
    fun readRows(t: CompoundTag,key: String) = TaskObjectiveCodec.readDeliveries(CompoundTag().apply { put("deliveries",checkNotNull(t.get(key)).copy()) })
}
