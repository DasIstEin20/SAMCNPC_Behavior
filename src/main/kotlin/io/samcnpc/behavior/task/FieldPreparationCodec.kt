package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.position
import io.samcnpc.behavior.task.InventoryWorkCodec.readPosition

internal object FieldPreparationCodec {
    fun definitionKeys(hasReturn: Boolean)=setOf("area","anchor","radius")+if(hasReturn) setOf("returnTo") else emptySet()
    fun writeDefinition(d: PrepareFieldTaskDefinition,t: CompoundTag) {
        t.put("area",WorkOrderCodec.area(d.area));t.put("anchor",position(d.anchor));t.putDouble("radius",d.travelRadius)
        d.returnTo?.let { t.put("returnTo",position(it)) }
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int)=PrepareFieldTaskDefinition(
        dimension,WorkOrderCodec.readArea(compound(t,"area")),readPosition(compound(t,"anchor")),
        InventoryWorkCodec.double(t,"radius"),if(t.contains("returnTo")) readPosition(compound(t,"returnTo")) else null,budget,version)

    fun write(s: FieldPreparationState)=CompoundTag().apply {
        putString("phase",s.phase.name);putInt("cursor",s.cursor);putString("detail",s.detail)
        s.stop?.let { putString("stop",it.name) }
        put("resources",InventoryLedgerCodec.write(s.resources))
        put("confirmed",ListTag().apply { for(p in s.confirmed) add(MiningOrderCodec.block(p)) })
        put("attempts",ListTag().apply { for((p,count) in s.attempts) add(CompoundTag().apply {
            put("position",MiningOrderCodec.block(p));putInt("count",count)
        }) })
    }
    fun read(t: CompoundTag,d: PrepareFieldTaskDefinition): FieldPreparationState {
        require(t.allKeys == setOf("phase","cursor","detail","resources","confirmed","attempts")+if(t.contains("stop")) setOf("stop") else emptySet()) { "unknown/missing field state" }
        val s=FieldPreparationState(InventoryLedgerCodec.read(compound(t,"resources")))
        s.phase=enumValueOf(t.getString("phase"));s.cursor=int(t,"cursor");s.detail=t.getString("detail")
        if(t.contains("stop")) s.stop=enumValueOf<FieldProblem>(t.getString("stop"))
        require(s.cursor in 0..d.cells.size && s.detail.length <= TaskRecord.MAX_DETAIL_LENGTH) { "unbounded field cursor/detail" }
        for(entry in InventoryWorkCodec.list(t,"confirmed",64)) {
            val p=MiningOrderCodec.readBlock(entry)
            require(p in d.cells && s.confirmed.add(p)) { "duplicate/unauthorized prepared soil" }
        }
        for(entry in InventoryWorkCodec.list(t,"attempts",64)) {
            InventoryWorkCodec.keys(entry,"position","count")
            val p=MiningOrderCodec.readBlock(compound(entry,"position"));val count=int(entry,"count")
            require(p in d.cells && count in 1..FieldPreparationState.MAX_ATTEMPTS_PER_CELL && s.attempts.put(p,count)==null) { "invalid/duplicate field attempt" }
        }
        require(s.stop == null || s.phase in setOf(FieldPhase.RETURN,FieldPhase.DONE)) { "stopped field would resume mutation" }
        if(s.stop == null) {
            require(d.cells.take(s.cursor).all { it in s.confirmed }) { "field cursor bypasses unconfirmed soil" }
            if(s.phase in setOf(FieldPhase.RETURN,FieldPhase.VERIFY,FieldPhase.DONE))
                require(s.confirmed == d.cells.toSet()) { "field verification lacks its complete soil plane" }
        }
        s.reconcileWorld=true
        return s
    }
}
