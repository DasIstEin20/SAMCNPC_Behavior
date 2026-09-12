package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.CompoundTag

/** Exact endpoint subtotals are factual provenance, never permission to replay a transfer. */
internal object InventoryTransferRows {
    fun record(rows: MutableMap<NpcBlockPosition, Map<String, Int>>, position: NpcBlockPosition, item: String, count: Int) {
        require(count in 1..2304 && (position in rows || rows.size < 8))
        val before = rows[position].orEmpty()
        val after = before + (item to ((before[item] ?: 0) + count))
        require(after.size <= 16 && after.values.sumOf { it.toLong() } <= 2304)
        rows[position] = java.util.Map.copyOf(after)
    }
    fun read(tag: CompoundTag, key: String): Map<NpcBlockPosition, Map<String, Int>> {
        val rows = linkedMapOf<NpcBlockPosition, Map<String, Int>>()
        for (entry in InventoryWorkCodec.list(tag,key,8)) {
            InventoryWorkCodec.keys(entry,"position","counts")
            val p = LumberjackTaskCodec.readPosition(InventoryWorkCodec.compound(entry,"position"))
            require(p.x in -29_999_984..29_999_984 && p.z in -29_999_984..29_999_984 && p.y in -2048..2048) { "inventory endpoint outside world bounds" }
            val counts = InventoryStateCodec.counts(entry,"counts")
            require(counts.isNotEmpty() && rows.put(p,counts) == null) { "empty/duplicate inventory endpoint row" }
        }
        return java.util.Map.copyOf(rows)
    }
    fun validate(rows: Map<NpcBlockPosition, Map<String, Int>>, totals: Map<String, Int>) {
        require((rows.values.flatMap { it.keys } + totals.keys).all { id -> rows.values.sumOf { (it[id] ?: 0).toLong() } == (totals[id] ?: 0).toLong() }) {
            "inventory endpoint rows differ from actual transfer counters"
        }
    }
}
