package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

internal object FishingTaskCodec {
    fun definitionKeys(returning: Boolean) = setOf("water", "standing", "catches", "anchor", "travelRadius", "pickupWaitTicks") +
        if (returning) setOf("returnTo") else emptySet()
    fun writeDefinition(d: FishingTaskDefinition, t: CompoundTag) {
        val water = CompoundTag();water.putInt("x", d.water.x);water.putInt("y", d.water.y);water.putInt("z", d.water.z)
        t.put("water", water);t.put("standing", InventoryWorkCodec.position(d.standing));t.put("anchor", InventoryWorkCodec.position(d.anchor))
        t.putInt("catches", d.catches);t.putDouble("travelRadius", d.travelRadius);t.putInt("pickupWaitTicks", d.pickupWaitTicks)
        d.returnTo?.let { t.put("returnTo", InventoryWorkCodec.position(it)) }
    }
    fun readDefinition(t: CompoundTag, dimension: String, budget: TaskBudget, version: Int): FishingTaskDefinition {
        val water = InventoryWorkCodec.compound(t, "water");InventoryWorkCodec.keys(water, "x", "y", "z")
        return FishingTaskDefinition(dimension, NpcBlockPosition(InventoryWorkCodec.int(water,"x"), InventoryWorkCodec.int(water,"y"), InventoryWorkCodec.int(water,"z")),
            InventoryWorkCodec.readPosition(InventoryWorkCodec.compound(t,"standing")), InventoryWorkCodec.int(t,"catches"),
            InventoryWorkCodec.readPosition(InventoryWorkCodec.compound(t,"anchor")), InventoryWorkCodec.double(t,"travelRadius"),
            if (t.contains("returnTo")) InventoryWorkCodec.readPosition(InventoryWorkCodec.compound(t,"returnTo")) else null,
            InventoryWorkCodec.int(t,"pickupWaitTicks"), budget, version)
    }
    fun write(s: FishingTaskState): CompoundTag {
        val t = CompoundTag();t.put("resources", InventoryLedgerCodec.write(s.resources));t.putString("phase",s.phase.name)
        t.putInt("casts",s.casts);t.putInt("caught",s.caught);t.putInt("collectedCatches",s.collectedCatches);t.putInt("collectTicks",s.collectTicks);t.putBoolean("pendingReel",s.pendingReel)
        val drops = ListTag()
        for ((id, count) in s.drops.toSortedMap()) {
            val row = CompoundTag();row.putString("item",id);row.putInt("count",count);row.putInt("baseline",checkNotNull(s.collectionBaseline[id]));drops.add(row)
        }
        t.put("drops",drops)
        val spawned=ListTag()
        for ((id,count) in s.spawned.toSortedMap()) { val row=CompoundTag();row.putString("item",id);row.putInt("count",count);spawned.add(row) }
        t.put("spawned",spawned);return t
    }
    fun read(t: CompoundTag, d: FishingTaskDefinition): FishingTaskState {
        InventoryWorkCodec.keys(t,"resources","phase","casts","caught","collectTicks","collectedCatches","pendingReel","drops","spawned")
        val phase = FishingPhase.entries.firstOrNull { it.name == t.getString("phase") } ?: throw IllegalArgumentException("unknown fishing phase")
        val s = FishingTaskState(InventoryLedgerCodec.read(InventoryWorkCodec.compound(t,"resources")),phase,
            InventoryWorkCodec.int(t,"casts"),InventoryWorkCodec.int(t,"caught"),InventoryWorkCodec.int(t,"collectTicks"),InventoryWorkCodec.int(t,"collectedCatches"),InventoryWorkCodec.bool(t,"pendingReel"))
        for (row in InventoryWorkCodec.list(t,"drops",64)) {
            InventoryWorkCodec.keys(row,"item","count","baseline")
            val id=row.getString("item");require(s.drops.put(id,InventoryWorkCodec.int(row,"count"))==null) { "duplicate fishing drop identity" }
            s.collectionBaseline[id]=InventoryWorkCodec.int(row,"baseline")
        }
        for (row in InventoryWorkCodec.list(t,"spawned",HarvestResources.MAX_KINDS)) {
            InventoryWorkCodec.keys(row,"item","count")
            require(s.spawned.put(row.getString("item"),InventoryWorkCodec.int(row,"count"))==null) { "duplicate fishing receipt identity" }
        }
        val problem=s.validationProblem(d);require(problem==null) { problem.orEmpty() };return s
    }
}
