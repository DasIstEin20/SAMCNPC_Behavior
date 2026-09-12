package io.samcnpc.behavior.task

import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.bool
import io.samcnpc.behavior.task.InventoryWorkCodec.position
import io.samcnpc.behavior.task.InventoryWorkCodec.readPosition

internal object FarmOrderCodec {
    fun keys(hasReturn: Boolean) = setOf("work","destinations","quantity","anchor","radius") + if (hasReturn) setOf("returnTo") else emptySet()
    fun writeDefinition(d: FarmTaskDefinition,t: CompoundTag) {
        t.put("work",write(d.work)); t.put("destinations",LumberjackSupplyCodec.writeChoices(d.destinations)); t.putInt("quantity",d.quantity)
        t.put("anchor",position(d.anchor)); t.putDouble("radius",d.travelRadius); d.returnTo?.let { t.put("returnTo",position(it)) }
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int) = FarmTaskDefinition(dimension,
        read(compound(t,"work")),requireNotNull(LumberjackSupplyCodec.readChoices(compound(t,"destinations"))),int(t,"quantity"),readPosition(compound(t,"anchor")),
        InventoryWorkCodec.double(t,"radius"),if (t.contains("returnTo")) readPosition(compound(t,"returnTo")) else null,budget,version)
    private fun write(w: FarmWorkOrder) = CompoundTag().apply {
        put("area",WorkOrderCodec.area(w.area)); putString("crop",w.crop.name); putString("mode",w.mode.name); putInt("cycles",w.cycles)
        putBoolean("prepareSoil",w.prepareSoil); putInt("keepSeeds",w.keepSeeds); putInt("sourceKeepSeeds",w.sourceKeepSeeds)
        putInt("growthWaitTicks",w.growthWaitTicks); putInt("growthCheckTicks",w.growthCheckTicks)
        w.seedSources?.let { put("seedSources",LumberjackSupplyCodec.writeChoices(it)) }
    }
    private fun read(t: CompoundTag): FarmWorkOrder {
        require(t.allKeys == setOf("area","crop","mode","cycles","prepareSoil","keepSeeds","sourceKeepSeeds","growthWaitTicks","growthCheckTicks") + (if (t.contains("seedSources")) setOf("seedSources") else emptySet())) { "unknown/missing farm work field" }
        val w=FarmWorkOrder(WorkOrderCodec.readArea(compound(t,"area")),enumValueOf(t.getString("crop")),enumValueOf(t.getString("mode")),int(t,"cycles"),bool(t,"prepareSoil"),
            if (t.contains("seedSources")) requireNotNull(LumberjackSupplyCodec.readChoices(compound(t,"seedSources"))) else null,
            int(t,"keepSeeds"),int(t,"sourceKeepSeeds"),int(t,"growthWaitTicks"),int(t,"growthCheckTicks"))
        require(w.validationProblem() == null) { w.validationProblem().orEmpty() }
        return w
    }
}
