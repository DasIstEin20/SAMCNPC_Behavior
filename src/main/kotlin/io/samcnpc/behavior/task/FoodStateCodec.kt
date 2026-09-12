package io.samcnpc.behavior.task

import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.bool
import io.samcnpc.behavior.task.InventoryWorkCodec.list
import io.samcnpc.behavior.task.InventoryWorkCodec.position
import io.samcnpc.behavior.task.InventoryWorkCodec.readPosition
import io.samcnpc.behavior.task.MiningOrderCodec.block
import io.samcnpc.behavior.task.MiningOrderCodec.readBlock

/** No paths, active Core operations or leases survive a load; cargo and factual effects do. */
internal object FoodStateCodec {
    fun write(s: FoodTaskState) = CompoundTag().apply {
        ProducedCargoCodec.write(s,this)
        putString("phase",s.phase.name); putInt("cursor",s.cursor); putInt("collectionTicks",s.collectionTicks); putBoolean("exhausted",s.exhausted)
        s.stop?.let { putString("stop",it.name) }; s.berry?.let { put("berry",block(it)) }; s.collectionOrigin?.let { put("collectionOrigin",position(it)) }
        s.selectedSource?.let { put("source",block(it)) }; put("sourceCheckpoints",ProducedCargoCodec.checkpoints(s.sourceCheckpoints))
        put("withdrawals",TaskObjectiveCodec.deliveries(s.withdrawals)); put("food",WorkOrderCodec.strings(s.knownFood)); put("pickups",LumberjackTaskCodec.counts(s.pickups))
        put("harvested",ListTag().apply { for (p in s.harvested) add(block(p)) })
        s.huntTarget?.let { putUUID("huntTarget",it) }; s.huntFrame?.let { putUUID("huntFrame",it) }
        put("hunts",ListTag().apply { for ((id,kills) in s.hunts) add(CompoundTag().apply { putUUID("target",id); putInt("kills",kills) }) })
    }
    fun read(t: CompoundTag,d: FoodTaskDefinition): FoodTaskState {
        val required=setOf("resources","deliveries","checkpoints","phase","cursor","collectionTicks","exhausted","sourceCheckpoints","withdrawals","food","pickups","harvested","hunts")
        val optional=setOf("container","stop","berry","collectionOrigin","source","huntTarget","huntFrame")
        require(t.allKeys == required+optional.filter(t::contains)) { "unknown/missing food state" }
        val s=FoodTaskState(ProducedResourcesCodec.read(compound(t,"resources")))
        ProducedCargoCodec.readInto(t,s)
        s.phase=enumValueOf(t.getString("phase")); s.cursor=int(t,"cursor"); s.collectionTicks=int(t,"collectionTicks"); s.exhausted=bool(t,"exhausted")
        if (t.contains("stop")) s.stop=enumValueOf<FoodProblem>(t.getString("stop"))
        require(s.cursor in 0..d.volume && s.collectionTicks in 0..FoodTaskState.COLLECTION_TICKS) { "food scan/window exceeds bounds" }
        if (t.contains("berry")) s.berry=readBlock(compound(t,"berry"))
        if (t.contains("collectionOrigin")) s.collectionOrigin=readPosition(compound(t,"collectionOrigin"))
        if (t.contains("source")) s.selectedSource=readBlock(compound(t,"source"))
        s.sourceCheckpoints.putAll(ProducedCargoCodec.readCheckpoints(t,"sourceCheckpoints")); s.withdrawals.putAll(ProducedCargoCodec.readRows(t,"withdrawals"))
        s.knownFood.addAll(WorkOrderCodec.readStrings(t,"food")); s.pickups.putAll(LumberjackTaskCodec.readCounts(t,"pickups"))
        for (v in list(t,"harvested",FoodTaskState.MAX_BUSHES)) require(s.harvested.add(readBlock(v))) { "duplicate harvested bush" }
        for (v in list(t,"hunts",16)) {
            InventoryWorkCodec.keys(v,"target","kills"); require(v.hasUUID("target")) { "invalid hunt identity" }
            val kills=int(v,"kills"); require(kills in 0..1 && s.hunts.put(v.getUUID("target"),kills) == null) { "invalid/duplicate hunt result" }
        }
        if (t.contains("huntTarget")) { require(t.hasUUID("huntTarget")); s.huntTarget=t.getUUID("huntTarget") }
        if (t.contains("huntFrame")) { require(t.hasUUID("huntFrame")); s.huntFrame=t.getUUID("huntFrame") }
        require((s.berry != null) == (s.phase == FoodPhase.BERRY)) { "food phase differs from bush intent" }
        require((s.collectionOrigin != null) == (s.phase == FoodPhase.COLLECT || s.phase == FoodPhase.HUNT)) { "food phase differs from collection origin" }
        require((s.huntTarget != null) == (s.phase == FoodPhase.HUNT) && (s.huntFrame != null) == (s.huntTarget != null) && s.huntTarget !in s.hunts) { "invalid active hunt intent" }
        require(s.collectionOrigin == null || d.inWork(checkNotNull(s.collectionOrigin))) { "food collection outside work area" }
        require(s.phase == FoodPhase.COLLECT || s.collectionTicks == 0) { "unexpected food collection window" }
        require(s.selectedContainer == null || s.phase == FoodPhase.DEPOSIT && s.selectedContainer in d.destinations.positions) { "unauthorized food recipient" }
        require(s.stop == null || s.exhausted && s.phase in setOf(FoodPhase.DEPOSIT,FoodPhase.RETURN)) { "food stop differs from terminal work phase" }
        require(s.knownFood.all(d.outputs::matches) && s.pickups.keys.all { it in s.knownFood }) { "unauthorized food credit" }
        require(s.withdrawals.keys.all { it in s.sourceCheckpoints }) { "food withdrawal lacks source identity" }
        for ((id,row) in s.resources.entries) {
            val physical=s.resources.physical.entries.getValue(id)
            require((s.pickups[id] ?: 0) == physical.gathered-row.protectedGathered) { "food pickup history differs from observed gains" }
            require(s.withdrawals.values.sumOf { (it[id] ?: 0).toLong() } == row.sourceOutput.toLong()) { "food source history differs from output origin" }
            require(id in s.knownFood || row.output == 0 && row.deliveredOutput == 0 && row.sourceOutput == 0) { "nonfood carries food output credit" }
        }
        when (val w=d.work) {
            is FoodWorkOrder.Berries -> {
                require(s.harvested.all(w.area::contains) && (s.berry == null || w.area.contains(checkNotNull(s.berry)) && s.berry !in s.harvested)) { "berry intent/evidence outside work area or repeated" }
                require(s.sourceCheckpoints.isEmpty() && s.withdrawals.isEmpty() && s.hunts.isEmpty() && s.huntTarget == null) { "wild gathering contains source/hunting work" }
            }
            is FoodWorkOrder.Stored -> {
                require(s.pickups.isEmpty() && s.harvested.isEmpty() && s.hunts.isEmpty() && s.cursor == 0 && s.collectionOrigin == null) { "stored food contains gathering/hunting work" }
                require(s.selectedSource == null || s.phase == FoodPhase.WITHDRAW && s.selectedSource in w.sources.positions) { "unauthorized food source" }
            }
            is FoodWorkOrder.Hunt -> {
                require(s.hunts.size+(if (s.huntTarget != null) 1 else 0) <= w.limit && s.harvested.isEmpty() && s.cursor == 0 && s.sourceCheckpoints.isEmpty() && s.withdrawals.isEmpty()) { "hunting state exceeds explicit work" }
            }
            is FoodWorkOrder.Drops -> require(s.harvested.isEmpty() && s.hunts.isEmpty() && s.cursor == 0 && s.sourceCheckpoints.isEmpty() && s.withdrawals.isEmpty() && s.collectionOrigin == null) { "drop gathering contains harvest/source/hunting work" }
        }
        require(d.work is FoodWorkOrder.Stored || s.selectedSource == null && s.phase != FoodPhase.WITHDRAW) { "nonstored food has withdrawal intent" }
        require(d.work is FoodWorkOrder.Hunt || s.huntTarget == null && s.phase != FoodPhase.HUNT) { "gathering cannot authorize hunting" }
        require(d.work is FoodWorkOrder.Berries || s.berry == null && s.phase != FoodPhase.BERRY) { "unexpected berry intent" }
        return s
    }
}
