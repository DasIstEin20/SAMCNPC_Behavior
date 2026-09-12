package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.list
import io.samcnpc.behavior.task.MiningOrderCodec.block
import io.samcnpc.behavior.task.MiningOrderCodec.readBlock

internal object PlantingStateCodec {
    fun write(s: PlantingTaskState)=CompoundTag().apply {
        put("resources",InventoryLedgerCodec.write(s.resources)); putString("phase",s.phase.name); putInt("cursor",s.cursor); putString("detail",s.detail)
        s.selected?.let { put("selected",block(it)) }; s.supplyFrame?.let { putUUID("supplyFrame",it) }; s.stop?.let { putString("stop",it.name) }
        put("plots",ListTag().apply { for(plot in s.plots.values) add(CompoundTag().apply {
            put("base",block(plot.base)); put("initial",positions(plot.initial)); put("placed",positions(plot.placed))
        }) })
        put("skipped",ListTag().apply { for((p,why) in s.skipped) add(CompoundTag().apply { put("base",block(p)); putString("reason",why.name) }) })
        put("containers",ListTag().apply { for((p,checkpoint) in s.checkpoints) add(CompoundTag().apply {
            put("position",block(p)); putString("block",checkpoint.blockId); putInt("size",checkpoint.size); put("withdrawn",LumberjackTaskCodec.counts(s.withdrawals[p].orEmpty())); put("delivered",LumberjackTaskCodec.counts(s.deliveries[p].orEmpty()))
        }) })
    }
    fun read(t: CompoundTag,d: PlantingTaskDefinition): PlantingTaskState {
        require(t.allKeys == setOf("resources","phase","cursor","detail","plots","skipped","containers")+setOf("selected","supplyFrame","stop").filter(t::contains)) { "unknown/missing planting state" }
        val s=PlantingTaskState(InventoryLedgerCodec.read(compound(t,"resources")))
        s.phase=enumValueOf(t.getString("phase")); s.cursor=int(t,"cursor"); s.detail=t.getString("detail")
        require(s.cursor in 0..d.work.sites.size && s.detail.length <= TaskRecord.MAX_DETAIL_LENGTH && t.contains("detail",Tag.TAG_STRING.toInt())) { "planting cursor/detail exceeds bounds" }
        if(t.contains("selected")) s.selected=readBlock(compound(t,"selected"))
        if(t.contains("supplyFrame")) { require(t.hasUUID("supplyFrame")); s.supplyFrame=t.getUUID("supplyFrame") }
        if(t.contains("stop")) s.stop=enumValueOf<PlantingProblem>(t.getString("stop"))
        for(row in list(t,"plots",128)) {
            InventoryWorkCodec.keys(row,"base","initial","placed")
            val base=readBlock(compound(row,"base")); val initial=readPositions(row,"initial"); val placed=readPositions(row,"placed")
            val plot=PlantingPlot(base,initial); plot.placed.addAll(placed)
            require(base in d.work.sites.take(s.cursor) && s.plots.put(base,plot) == null) { "duplicate or unauthorized planted layout" }
            require((initial+placed).all { it in d.work.species.footprint(base) } && initial.intersect(placed).isEmpty()) { "planting journal escaped or repeated a layout member" }
            require(d.work.mode == PlantingMode.GAPS || initial.isEmpty()) { "patch work has preexisting layout members" }
            require(initial.size < d.work.species.layoutSize*d.work.species.layoutSize) { "preexisting complete layout cannot gain planting credit" }
        }
        for(row in list(t,"skipped",128)) {
            InventoryWorkCodec.keys(row,"base","reason"); val base=readBlock(compound(row,"base"))
            require(base in d.work.sites.take(s.cursor) && base !in s.plots && s.skipped.put(base,enumValueOf(row.getString("reason"))) == null) { "invalid skipped planting layout" }
        }
        require(s.plots.size+s.skipped.size == s.cursor) { "planting cursor lacks factual site history" }
        require((s.selected != null) == (s.phase == PlantingPhase.PLACE || s.phase == PlantingPhase.SUPPLY) && (s.selected == null || s.selected in s.plots && !checkNotNull(s.plots[s.selected]).complete(d.work.species))) { "pending planting layout differs from its phase" }
        require((s.supplyFrame != null) == (s.phase == PlantingPhase.SUPPLY) && (s.supplyFrame == null || d.work.sources != null)) { "planting supply lacks permission or exact child identity" }
        require(s.stop == null || s.phase == PlantingPhase.RETURN || s.phase == PlantingPhase.DONE) { "planting stop still has work control" }
        val withdrawn=linkedMapOf<String,Int>(); val delivered=linkedMapOf<String,Int>()
        for(row in list(t,"containers",32)) {
            InventoryWorkCodec.keys(row,"position","block","size","withdrawn","delivered")
            val position=readBlock(compound(row,"position")); val blockId=row.getString("block"); val size=int(row,"size")
            require(size in 1..64 && HarvestResources.validCounts(mapOf(blockId to 1)) && position !in s.checkpoints) { "invalid sapling source checkpoint" }
            val items=LumberjackTaskCodec.readCounts(row,"withdrawn"); val sent=LumberjackTaskCodec.readCounts(row,"delivered")
            require(items.isNotEmpty() || sent.isNotEmpty()) { "empty planting container transfer record" }
            s.checkpoints[position]=ContainerCheckpoint(blockId,size)
            if(items.isNotEmpty()) s.withdrawals[position]=items
            if(sent.isNotEmpty()) s.deliveries[position]=sent
            for((id,count) in items) withdrawn[id]=(withdrawn[id] ?: 0)+count
            for((id,count) in sent) delivered[id]=(delivered[id] ?: 0)+count
        }
        require(s.resources.entries.filterValues { it.supplied > 0 }.mapValues { it.value.supplied } == withdrawn &&
            s.resources.entries.filterValues { it.delivered > 0 }.mapValues { it.value.delivered } == delivered) { "planting resources differ from actual container transfers" }
        require(s.planted() == (s.resources.entries[d.work.species.blockId]?.consumed ?: 0)) { "planted saplings differ from actual item consumption" }
        if(s.phase == PlantingPhase.SELECT) require(s.plots.values.all { it.complete(d.work.species) }) { "planting selection skipped unfinished physical layout" }
        if(s.phase == PlantingPhase.DONE && s.stop == null) require(s.goal(d)) { "finished planting lacks its actual layout quota" }
        s.reconcileWorld=true
        return s
    }
    private fun positions(values: Collection<NpcBlockPosition>)=ListTag().apply { for(p in values) add(block(p)) }
    private fun readPositions(t: CompoundTag,key: String): Set<NpcBlockPosition> {
        val values=list(t,key,4).map(::readBlock); require(values.distinct().size == values.size) { "duplicate sapling member" }; return values.toSet()
    }
}
