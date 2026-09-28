package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.*

/** Strict common inventory request encoding, shared by task definitions and amendments. */
internal object InventoryWorkCodec {
    fun write(work: InventoryWork) = CompoundTag().apply {
        putString("kind", work.kind.name)
        when (work) {
            is EnsureItems -> {
                putString("query", work.query.encode()); putInt("count", work.count); putDouble("minimumDurability", work.minimumDurability)
                putInt("sourceReserve", work.sourceReserve)
                work.destination?.let { putString("destination", it.name) }
                work.containers?.let { put("containers", LumberjackSupplyCodec.writeChoices(it)) }
            }
            is CollectContainer -> {
                put("source", LumberjackTaskCodec.position(work.source)); putInt("maxItems", work.maxItems)
            }
            is SupplyStock -> {
                put("containers", LumberjackSupplyCodec.writeChoices(work.containers))
                put("items", ListTag().apply { for (need in work.needs) add(CompoundTag().apply {
                    putString("item", need.itemId); putInt("minimum", need.minimum); putInt("target", need.target); putInt("reserve", need.sourceReserve)
                }) })
            }
            is UnloadExcess -> {
                if (work.minimumFreeSlots > 0) putInt("minimumFreeSlots", work.minimumFreeSlots)
                put("containers", LumberjackSupplyCodec.writeChoices(work.containers))
                put("items", ListTag().apply { for (reserve in work.reserves) add(CompoundTag().apply {
                    putString("item", reserve.itemId); putInt("reserve", reserve.keep)
                }) })
            }
            is PickupNearby -> {
                put("items", ListTag().apply { for (id in work.itemIds) add(CompoundTag().apply { putString("item", id) }) })
                putDouble("radius", work.radius); putInt("maxItems", work.maxItems)
            }
        }
    }
    fun read(tag: CompoundTag): InventoryWork {
        val items = if (tag.getString("kind") in setOf("COLLECT", "ENSURE")) emptyList() else list(tag, "items", 16)
        val work = when (tag.getString("kind")) {
            "ENSURE" -> {
                require(tag.allKeys == setOf("kind", "query", "count", "minimumDurability", "sourceReserve") + setOf("destination", "containers").filter { tag.contains(it) })
                require(tag.contains("query", Tag.TAG_STRING.toInt()))
                EnsureItems(io.samcnpc.behavior.api.ItemQuery.parse(tag.getString("query")), int(tag, "count"),
                    if (tag.contains("containers")) choices(tag) else null, double(tag, "minimumDurability"),
                    if (tag.contains("destination")) io.samcnpc.core.api.NpcEquipmentDestination.valueOf(tag.getString("destination")) else null, int(tag, "sourceReserve"))
            }
            "COLLECT" -> {
                keys(tag, "kind", "source", "maxItems")
                CollectContainer(LumberjackTaskCodec.readPosition(compound(tag, "source")), int(tag, "maxItems"))
            }
            "SUPPLY" -> {
                keys(tag, "kind", "containers", "items")
                SupplyStock(items.map { keys(it, "item", "minimum", "target", "reserve"); StockNeed(it.getString("item"), int(it, "minimum"), int(it, "target"), int(it, "reserve")) }, choices(tag))
            }
            "UNLOAD" -> {
                require(tag.allKeys == setOf("kind", "containers", "items") + if (tag.contains("minimumFreeSlots")) setOf("minimumFreeSlots") else emptySet())
                UnloadExcess(items.map { keys(it, "item", "reserve"); ItemReserve(it.getString("item"), int(it, "reserve")) }, choices(tag), if (tag.contains("minimumFreeSlots")) int(tag, "minimumFreeSlots") else 0)
            }
            "PICKUP" -> {
                keys(tag, "kind", "items", "radius", "maxItems")
                PickupNearby(items.map { keys(it, "item"); it.getString("item") }, double(tag, "radius"), int(tag, "maxItems"))
            }
            else -> throw IllegalArgumentException("unknown inventory work kind")
        }
        require(work.validationProblem() == null) { "invalid inventory work parameters" }
        return work
    }
    fun definitionKeys() = setOf("work", "anchor", "returnTo", "travelRadius", "workTicks", "maxSteps")
    fun writeDefinition(value: InventoryTaskDefinition, tag: CompoundTag) {
        tag.put("work", write(value.work)); tag.put("anchor", position(value.anchor)); tag.put("returnTo", position(value.returnTo))
        tag.putDouble("travelRadius", value.travelRadius); tag.putInt("workTicks", value.workTicks); tag.putInt("maxSteps", value.maxSteps)
    }
    fun readDefinition(tag: CompoundTag, dimension: String, budget: TaskBudget, version: Int) = InventoryTaskDefinition(dimension,
        read(compound(tag, "work")), readPosition(compound(tag, "anchor")), readPosition(compound(tag, "returnTo")), double(tag, "travelRadius"), int(tag, "workTicks"), int(tag, "maxSteps"), budget, version)
    fun writePolicy(value: TaskLogisticsPolicy) = CompoundTag().apply {
        value.anchor?.let { put("anchor", position(it)) }
        value.supply?.let { put("supply", write(it)) }; value.unload?.let { put("unload", write(it)) }; value.pickup?.let { put("pickup", write(it)) }
        value.preparation?.let { put("preparation", write(it)) }
        putDouble("travelRadius", value.travelRadius); putInt("workTicks", value.workTicks); putInt("durationTicks", value.durationTicks)
        putInt("cooldownTicks", value.cooldownTicks); putInt("maxSteps", value.maxSteps)
    }
    fun readPolicy(tag: CompoundTag): TaskLogisticsPolicy {
        require(tag.allKeys == setOf("travelRadius", "workTicks", "durationTicks", "cooldownTicks", "maxSteps") + setOf("anchor", "supply", "unload", "pickup", "preparation").filter { tag.contains(it) }) { "unknown/missing logistics policy" }
        val supply = if (tag.contains("supply")) read(compound(tag, "supply")) else null
        val unload = if (tag.contains("unload")) read(compound(tag, "unload")) else null
        val pickup = if (tag.contains("pickup")) read(compound(tag, "pickup")) else null
        val preparation = if (tag.contains("preparation")) read(compound(tag, "preparation")) else null
        require(preparation == null || preparation is EnsureItems) { "preparation policy must be ENSURE" }
        require((supply == null || supply is SupplyStock) && (unload == null || unload is UnloadExcess) && (pickup == null || pickup is PickupNearby)) { "logistics policy kind differs from its slot" }
        val value = TaskLogisticsPolicy(if (tag.contains("anchor")) readPosition(compound(tag, "anchor")) else null,
            supply as? SupplyStock, unload as? UnloadExcess, pickup as? PickupNearby, double(tag, "travelRadius"),
            int(tag, "workTicks"), int(tag, "durationTicks"), int(tag, "cooldownTicks"), int(tag, "maxSteps"), preparation as? EnsureItems)
        require(value.validationProblem() == null) { "invalid logistics policy" }; return value
    }
    private fun choices(tag: CompoundTag) = LumberjackSupplyCodec.readChoices(compound(tag, "containers")) ?: throw IllegalArgumentException("inventory work needs authorized containers")
    internal fun keys(tag: CompoundTag, vararg keys: String) { require(tag.allKeys == keys.toSet()) { "unknown/missing inventory fields" } }
    internal fun int(tag: CompoundTag, key: String) = TaskChangeCodec.integer(tag, key)
    internal fun compound(tag: CompoundTag, key: String) = TaskChangeCodec.compound(tag, key)
    internal fun list(tag: CompoundTag, key: String, limit: Int): List<CompoundTag> {
        val value = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing inventory list $key")
        require(value.size <= limit && (value.isEmpty() || value.elementType == Tag.TAG_COMPOUND)) { "invalid/oversized inventory list $key" }
        return value.map { it as CompoundTag }
    }
    internal fun double(tag: CompoundTag, key: String): Double {
        require(tag.contains(key, Tag.TAG_DOUBLE.toInt()) && tag.getDouble(key).isFinite()) { "invalid inventory coordinate/range $key" }
        return tag.getDouble(key)
    }
    internal fun bool(tag: CompoundTag, key: String): Boolean {
        require(tag.contains(key, Tag.TAG_BYTE.toInt()) && tag.getByte(key).toInt() in 0..1) { "invalid inventory boolean $key" }
        return tag.getBoolean(key)
    }
    internal fun position(p: NpcPosition) = CompoundTag().apply { putDouble("x", p.x); putDouble("y", p.y); putDouble("z", p.z) }
    internal fun readPosition(t: CompoundTag): NpcPosition { keys(t, "x", "y", "z"); return NpcPosition(double(t, "x"), double(t, "y"), double(t, "z")) }
}
