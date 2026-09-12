package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.bool
import io.samcnpc.behavior.task.InventoryWorkCodec.list
import io.samcnpc.behavior.task.MiningOrderCodec.block
import io.samcnpc.behavior.task.MiningOrderCodec.readBlock

internal object FarmStateCodec {
    fun write(s: FarmTaskState) = CompoundTag().apply {
        ProducedCargoCodec.write(s,this)
        putString("phase",s.phase.name); putInt("cursor",s.cursor); putInt("cycle",s.cycle); putBoolean("preparing",s.preparing)
        putInt("collectionTicks",s.collectionTicks); putInt("growthRemaining",s.growthRemaining); putInt("nextGrowthCheck",s.nextGrowthCheck)
        putBoolean("exhausted",s.exhausted); s.stop?.let { putString("stop",it.name) }; s.target?.let { put("target",block(it)) }; s.supplyFrame?.let { putUUID("supplyFrame",it) }
        put("cycleDone",ListTag().apply { for (p in s.cycleDone) add(block(p)) })
        put("harvested",counts(s.harvested)); put("planted",counts(s.planted)); put("replanted",counts(s.replanted)); put("tilled",counts(s.tilled))
        put("pickups",LumberjackTaskCodec.counts(s.pickups)); s.stopDetail?.let { putString("stopDetail",it) }
    }
    fun read(t: CompoundTag,d: FarmTaskDefinition): FarmTaskState {
        val required=setOf("resources","deliveries","checkpoints","phase","cursor","cycle","preparing","collectionTicks","growthRemaining","nextGrowthCheck","exhausted","cycleDone","harvested","planted","replanted","tilled","pickups")
        require(t.allKeys == required+setOf("container","stop","stopDetail","target","supplyFrame").filter(t::contains)) { "unknown/missing farm state" }
        val s=FarmTaskState(ProducedResourcesCodec.read(compound(t,"resources")),d); ProducedCargoCodec.readInto(t,s)
        s.phase=enumValueOf(t.getString("phase")); s.cursor=int(t,"cursor"); s.cycle=int(t,"cycle"); s.preparing=bool(t,"preparing")
        s.collectionTicks=int(t,"collectionTicks"); s.growthRemaining=int(t,"growthRemaining"); s.nextGrowthCheck=int(t,"nextGrowthCheck"); s.exhausted=bool(t,"exhausted")
        if (t.contains("stop")) s.stop=enumValueOf<FarmProblem>(t.getString("stop"))
        if (t.contains("stopDetail")) { require(t.contains("stopDetail",Tag.TAG_STRING.toInt()) && s.stop != null); s.stopDetail=t.getString("stopDetail"); require(checkNotNull(s.stopDetail).length in 1..TaskRecord.MAX_DETAIL_LENGTH) }
        if (t.contains("target")) s.target=readBlock(compound(t,"target"))
        if (t.contains("supplyFrame")) { require(t.hasUUID("supplyFrame")); s.supplyFrame=t.getUUID("supplyFrame") }
        require(s.cursor in 0..d.work.cells.size && s.cycle in 0..d.work.cycles && s.collectionTicks in 0..FarmTaskState.COLLECTION_TICKS && s.growthRemaining in 0..d.work.growthWaitTicks && s.nextGrowthCheck in 0..d.work.growthCheckTicks) { "farm cursor/cycle/wait exceeds its bound" }
        for (v in list(t,"cycleDone",512)) require(s.cycleDone.add(readBlock(v))) { "duplicate farm cycle cell" }
        s.harvested.putAll(readCounts(t,"harvested",d.work.cycles)); s.planted.putAll(readCounts(t,"planted",d.work.cycles+1))
        s.replanted.putAll(readCounts(t,"replanted",d.work.cycles)); s.tilled.putAll(readCounts(t,"tilled",d.work.cycles+1))
        s.pickups.putAll(LumberjackTaskCodec.readCounts(t,"pickups"))
        val workPhases=setOf(FarmPhase.HARVEST,FarmPhase.COLLECT,FarmPhase.SOIL,FarmPhase.PLANT,FarmPhase.SUPPLY)
        require((s.target != null) == (s.phase in workPhases) && (s.target == null || s.target in d.work.cells)) { "farm phase differs from authorized pending cell" }
        require((s.supplyFrame != null) == (s.phase == FarmPhase.SUPPLY)) { "farm supply phase lacks its exact child identity" }
        require((s.cycleDone+s.harvested.keys+s.planted.keys+s.replanted.keys+s.tilled.keys).all { it in d.work.cells }) { "farm journal escaped the supplied field" }
        require(s.replanted.all { (p,count) -> count <= (s.harvested[p] ?: 0) && count <= (s.planted[p] ?: 0) }) { "replant credit exceeds actual harvest/placement" }
        if (s.phase == FarmPhase.COLLECT) require(s.target in s.cycleDone && (s.harvested[s.target] ?: 0) == s.cycle+1) { "crop collection lacks this cycle's actual removal" }
        if (s.phase == FarmPhase.HARVEST) require(s.target !in s.cycleDone) { "pending crop was already processed this cycle" }
        require(s.phase == FarmPhase.COLLECT || s.collectionTicks == 0) { "unexpected crop collection window" }
        require(s.selectedContainer == null || s.phase == FarmPhase.DEPOSIT && s.selectedContainer in d.destinations.positions) { "unauthorized farm recipient" }
        require(!s.preparing || d.work.mode == FarmMode.CULTIVATE && s.cycle == 0) { "unexpected initial cultivation state" }
        require(s.stop == null || s.exhausted && s.phase in setOf(FarmPhase.DEPOSIT,FarmPhase.RETURN)) { "farm stop differs from terminal work phase" }
        if (d.work.mode == FarmMode.HARVEST) require(s.planted.isEmpty() && s.replanted.isEmpty() && s.tilled.isEmpty() && s.supplyFrame == null && s.phase !in setOf(FarmPhase.PREPARE,FarmPhase.SOIL,FarmPhase.PLANT,FarmPhase.SUPPLY,FarmPhase.WAIT_GROWTH)) { "harvest-only work contains cultivation" }
        require(d.work.mode == FarmMode.CULTIVATE || s.phase != FarmPhase.PREPARE) { "unexpected field preparation phase" }
        require(d.work.prepareSoil || s.tilled.isEmpty()) { "farm lacks soil-preparation permission" }
        require(d.work.seedSources != null || s.supplyFrame == null) { "farm lacks seed-source permission" }
        require(s.pickups.keys.all { it in d.work.crop.collectedItems }) { "unexpected crop pickup credit" }
        for ((id,row) in s.resources.entries) {
            val physical=s.resources.physical.entries.getValue(id)
            require(row.sourceOutput == 0 && (s.pickups[id] ?: 0) == physical.gathered-row.protectedGathered) { "farm output origin differs from physical crop pickup" }
            require(id in d.work.crop.collectedItems || row.output == 0) { "noncrop carries output credit" }
            require(d.outputs.matches(id) || row.deliveredOutput == 0) { "non-yield item credited as delivered crop" }
        }
        val consumedSeeds=s.resources.physical.entries[d.work.crop.seedId]?.consumed ?: 0
        require(s.planted.values.sum() <= consumedSeeds) { "planted crops exceed actual seed consumption" }
        s.reconcileWorld=true
        return s
    }
    private fun counts(values: Map<NpcBlockPosition,Int>) = ListTag().apply { for ((p,c) in values) add(CompoundTag().apply { put("position",block(p)); putInt("count",c) }) }
    private fun readCounts(t: CompoundTag,key: String,max: Int): Map<NpcBlockPosition,Int> {
        val values=linkedMapOf<NpcBlockPosition,Int>()
        for (v in list(t,key,512)) {
            InventoryWorkCodec.keys(v,"position","count"); val p=readBlock(compound(v,"position")); val count=int(v,"count")
            require(count in 1..max && values.put(p,count) == null) { "duplicate/invalid farm cell counter" }
        }
        return values
    }
}
