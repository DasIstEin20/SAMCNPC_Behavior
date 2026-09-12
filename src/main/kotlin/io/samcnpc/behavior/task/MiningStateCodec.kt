package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.list
import io.samcnpc.behavior.task.InventoryWorkCodec.bool
import io.samcnpc.behavior.task.InventoryWorkCodec.keys
import io.samcnpc.behavior.task.MiningOrderCodec.block
import io.samcnpc.behavior.task.MiningOrderCodec.readBlock

/** Bounded work intent and evidence only. Core action IDs and navigation never cross this boundary. */
internal object MiningStateCodec {
    fun write(s: MiningTaskState) = CompoundTag().apply {
        put("resources",ProducedResourcesCodec.write(s.resources)); putString("phase",s.phase.name)
        putString("stopDetail",s.stopDetail); putInt("collectionTicks",s.collectionTicks); putBoolean("exhausted",s.exhausted); s.stop?.let { putString("stop",it.name) }
        s.target?.let { put("target",CompoundTag().apply { put("position",block(it.position)); putString("id",it.blockId) }) }
        s.selectedContainer?.let { put("container",block(it)) }; put("deliveries",TaskObjectiveCodec.deliveries(s.deliveries))
        put("checkpoints",ListTag().apply { for ((p,c) in s.checkpoints) add(CompoundTag().apply { put("position",block(p)); putString("block",c.blockId); putInt("size",c.size) }) })
        val selection=s.selection
        putInt("cursor",selection.cursor); putBoolean("limited",selection.limited); selection.veinAnchor?.let { put("veinAnchor",block(it)) }
        put("frontier",positions(selection.frontier)); put("examined",positions(selection.examined)); put("cleared",positions(selection.cleared))
        put("removed",ListTag().apply { for ((p,id) in selection.removed) add(CompoundTag().apply { put("position",block(p)); putString("id",id) }) })
        put("problems",ListTag().apply { for ((p,count) in selection.problems) add(CompoundTag().apply { putString("reason",p.name); putInt("count",count) }) })
    }
    fun read(t: CompoundTag,d: MiningTaskDefinition): MiningTaskState {
        require(t.allKeys == setOf("resources","phase","stopDetail","collectionTicks","exhausted","deliveries","checkpoints","cursor","limited","frontier","examined","cleared","removed","problems")+listOf("stop","target","container","veinAnchor").filter(t::contains)) { "unknown/missing mining state" }
        val s=MiningTaskState(ProducedResourcesCodec.read(compound(t,"resources")))
        s.stopDetail=t.getString("stopDetail"); require(t.contains("stopDetail",Tag.TAG_STRING.toInt()) && s.stopDetail.length <= 256) { "invalid mining stop detail" }
        s.phase=enumValueOf(t.getString("phase")); s.collectionTicks=int(t,"collectionTicks"); s.exhausted=bool(t,"exhausted")
        require(s.collectionTicks in 0..MiningTaskState.COLLECTION_TICKS) { "invalid mining collection window" }
        if (t.contains("stop")) s.stop=enumValueOf<MiningProblem>(t.getString("stop"))
        if (t.contains("target")) { val v=compound(t,"target"); keys(v,"position","id"); s.target=MiningSelectionResult.Target(readBlock(compound(v,"position")),v.getString("id")) }
        require((s.target != null) == (s.phase == MiningPhase.WORK || s.phase == MiningPhase.COLLECT)) { "mining target differs from phase" }
        val selection=s.selection; selection.cursor=int(t,"cursor"); selection.limited=bool(t,"limited")
        require(selection.cursor in 0..d.work.volume) { "invalid mining scan cursor" }
        if (t.contains("veinAnchor")) selection.veinAnchor=readBlock(compound(t,"veinAnchor"))
        selection.frontier.addAll(readPositions(t,"frontier")); selection.examined.addAll(readPositions(t,"examined")); selection.cleared.addAll(readPositions(t,"cleared"))
        for (v in list(t,"removed",MiningWorkOrder.MAX_REMOVED)) {
            keys(v,"position","id"); val p=readBlock(compound(v,"position")); val id=v.getString("id")
            require(d.work.canRemove(id) && selection.removed.put(p,id) == null) { "duplicate/unauthorized mined block" }
        }
        require(selection.frontier.size+selection.examined.size <= MiningWorkOrder.MAX_REMOVED) { "vein frontier exceeds bound" }
        require((selection.frontier+selection.examined+selection.cleared+selection.removed.keys).all(d.work.area::contains)) { "mining state outside allowed work cells" }
        require(selection.frontier.none { it in selection.examined || it in selection.removed }) { "vein frontier repeats examined work" }
        if (d.work.method == MiningMethod.VEIN) {
            require((selection.veinAnchor == null) == selection.removed.isEmpty()) { "vein anchor lacks actual removal" }
            require(selection.veinAnchor == null || selection.veinAnchor in selection.removed) { "vein anchor was not mined" }
            require((selection.frontier+selection.examined).all { p -> MiningSelection.neighbors(p).any { it in selection.removed } || p == selection.veinAnchor }) { "vein intent is disconnected from removed members" }
        } else require(selection.veinAnchor == null && selection.frontier.isEmpty() && selection.examined.isEmpty() && !selection.limited) { "non-vein contains connected search state" }
        if (d.work.method == MiningMethod.TUNNEL || d.work.method == MiningMethod.EXCAVATION) require(selection.cleared.containsAll(selection.removed.keys)) { "removed volume lacks clearance facts" }
        else require(selection.cleared.isEmpty()) { "non-volume contains clearance facts" }
        val target=s.target
        if (target != null) {
            require(d.work.area.contains(target.position) && d.work.canRemove(target.blockId)) { "unauthorized pending mining intent" }
            require(if (s.phase == MiningPhase.COLLECT) selection.removed[target.position] == target.blockId else target.position !in selection.removed) { "mining phase differs from removal evidence" }
        }
        require(!s.exhausted || if (d.work.method == MiningMethod.VEIN && selection.veinAnchor != null) selection.frontier.isEmpty() && !selection.limited else selection.cursor == d.work.volume) { "mining exhausted before selection ended" }
        for (v in list(t,"problems",MiningProblem.entries.size)) {
            keys(v,"reason","count"); val reason=enumValueOf<MiningProblem>(v.getString("reason")); val count=int(v,"count")
            require(count in 1..1_000_000 && selection.problems.put(reason,count) == null) { "invalid mining problem counter" }
        }
        require(s.stop == null || (selection.problems[s.stop] ?: 0) > 0) { "mining stop lacks diagnostic history" }
        if (t.contains("container")) s.selectedContainer=readBlock(compound(t,"container"))
        require(s.selectedContainer == null || s.phase == MiningPhase.DEPOSIT && s.selectedContainer in d.destinations.positions) { "unauthorized/stale mining recipient" }
        for (v in list(t,"checkpoints",32)) {
            keys(v,"position","block","size"); val p=readBlock(compound(v,"position")); val c=ContainerCheckpoint(v.getString("block"),int(v,"size"))
            require(c.size in 1..64 && HarvestResources.validCounts(mapOf(c.blockId to 1)) && s.checkpoints.put(p,c) == null) { "invalid mining recipient identity" }
        }
        s.deliveries.putAll(TaskObjectiveCodec.readDeliveries(t))
        require(s.deliveries.keys.all { it in s.checkpoints }) { "mining delivery lacks observed recipient identity" }
        val entries=s.resources.physical.entries
        require((entries.keys+s.deliveries.values.flatMap { it.keys }).all { id -> s.deliveries.values.sumOf { (it[id] ?: 0).toLong() } == (entries[id]?.delivered ?: 0).toLong() }) { "mining delivery rows differ from physical transfers" }
        require(s.resources.entries.all { (id,row) -> row.sourceOutput == 0 && (d.outputs.matches(id) || row.deliveredOutput == 0) }) { "mining has unauthorized source/output credit" }
        s.reconcileWorld=true
        return s
    }
    private fun positions(values: Collection<NpcBlockPosition>) = ListTag().apply { for (p in values) add(block(p)) }
    private fun readPositions(t: CompoundTag,key: String): List<NpcBlockPosition> {
        val values=list(t,key,MiningWorkOrder.MAX_REMOVED).map(::readBlock)
        require(values.distinct().size == values.size) { "duplicate mining position" }; return values
    }
}
