package io.samcnpc.behavior.task

import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.position
import io.samcnpc.behavior.task.InventoryWorkCodec.readPosition

internal object PlantingOrderCodec {
    fun readNested(t: CompoundTag): PlantingTaskDefinition {
        require(t.getString("id") == PlantingTaskDefinition.ID) { "nested work must be a finite planting definition" }
        return TaskCodec.readDefinition(t) as PlantingTaskDefinition
    }
    fun keys(hasReturn: Boolean)=setOf("work","quantity","anchor","radius")+if(hasReturn) setOf("returnTo") else emptySet()
    fun writeDefinition(d: PlantingTaskDefinition,t: CompoundTag) {
        t.put("work",write(d.work)); t.putInt("quantity",d.quantity); t.put("anchor",position(d.anchor)); t.putDouble("radius",d.travelRadius)
        d.returnTo?.let { t.put("returnTo",position(it)) }
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int)=PlantingTaskDefinition(dimension,read(compound(t,"work")),int(t,"quantity"),readPosition(compound(t,"anchor")),
        InventoryWorkCodec.double(t,"radius"),if(t.contains("returnTo")) readPosition(compound(t,"returnTo")) else null,budget,version)
    fun write(w: PlantingWorkOrder)=CompoundTag().apply {
        put("area",WorkOrderCodec.area(w.area)); putString("species",w.species.name); putString("mode",w.mode.name); putInt("spacing",w.spacing)
        putInt("keepSaplings",w.keepSaplings); putInt("sourceKeep",w.sourceKeep)
        w.positions?.let { positions -> put("positions",ListTag().apply { for(p in positions) add(MiningOrderCodec.block(p)) }) }
        w.sources?.let { put("sources",LumberjackSupplyCodec.writeChoices(it)) }
    }
    fun read(t: CompoundTag): PlantingWorkOrder {
        require(t.allKeys == setOf("area","species","mode","spacing","keepSaplings","sourceKeep")+setOf("positions","sources").filter(t::contains)) { "unknown/missing planting work field" }
        val w=PlantingWorkOrder(WorkOrderCodec.readArea(compound(t,"area")),enumValueOf(t.getString("species")),enumValueOf(t.getString("mode")),int(t,"spacing"),
            if(t.contains("positions")) InventoryWorkCodec.list(t,"positions",128).map(MiningOrderCodec::readBlock) else null,
            if(t.contains("sources")) requireNotNull(LumberjackSupplyCodec.readChoices(compound(t,"sources"))) else null,int(t,"keepSaplings"),int(t,"sourceKeep"))
        require(w.validationProblem() == null && w.sites.size in 1..128) { w.validationProblem() ?: "no bounded planting sites" }
        return w
    }
}
