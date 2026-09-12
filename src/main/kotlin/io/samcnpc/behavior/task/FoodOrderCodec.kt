package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcEntityTypeFilter
import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.position
import io.samcnpc.behavior.task.InventoryWorkCodec.readPosition

internal object FoodOrderCodec {
    fun keys(hasReturn: Boolean) = setOf("work","outputs","destinations","quantity","keepFood","anchor","radius") + if (hasReturn) setOf("returnTo") else emptySet()
    fun writeDefinition(d: FoodTaskDefinition,t: CompoundTag) {
        t.put("work",write(d.work)); t.put("outputs",WorkOrderCodec.strings(d.outputs.values)); t.put("destinations",LumberjackSupplyCodec.writeChoices(d.destinations))
        t.putInt("quantity",d.quantity); t.putInt("keepFood",d.keepFood); t.put("anchor",position(d.anchor)); t.putDouble("radius",d.travelRadius)
        d.returnTo?.let { t.put("returnTo",position(it)) }
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int) = FoodTaskDefinition(dimension,
        read(compound(t,"work")),WorkResourceIds(WorkOrderCodec.readStrings(t,"outputs")),requireNotNull(LumberjackSupplyCodec.readChoices(compound(t,"destinations"))),
        int(t,"quantity"),int(t,"keepFood"),readPosition(compound(t,"anchor")),InventoryWorkCodec.double(t,"radius"),
        if (t.contains("returnTo")) readPosition(compound(t,"returnTo")) else null,budget,version)
    private fun write(work: FoodWorkOrder) = CompoundTag().apply {
        work.area?.let { put("area",WorkOrderCodec.area(it)) }
        when (work) {
            is FoodWorkOrder.Drops -> putString("mode","drops")
            is FoodWorkOrder.Berries -> putString("mode","berries")
            is FoodWorkOrder.Stored -> { putString("mode","stored"); put("sources",LumberjackSupplyCodec.writeChoices(work.sources)); putInt("sourceKeep",work.sourceKeep) }
            is FoodWorkOrder.Hunt -> { putString("mode","hunt"); put("types",WorkOrderCodec.strings(work.targets.typeIds)); put("tags",WorkOrderCodec.strings(work.targets.tagIds)); putInt("limit",work.limit) }
        }
    }
    private fun read(t: CompoundTag): FoodWorkOrder = when (t.getString("mode")) {
        "drops" -> { InventoryWorkCodec.keys(t,"mode","area"); FoodWorkOrder.Drops(WorkOrderCodec.readArea(compound(t,"area"))) }
        "berries" -> { InventoryWorkCodec.keys(t,"mode","area"); FoodWorkOrder.Berries(WorkOrderCodec.readArea(compound(t,"area"))) }
        "stored" -> { InventoryWorkCodec.keys(t,"mode","sources","sourceKeep"); FoodWorkOrder.Stored(requireNotNull(LumberjackSupplyCodec.readChoices(compound(t,"sources"))),int(t,"sourceKeep")) }
        "hunt" -> { InventoryWorkCodec.keys(t,"mode","area","types","tags","limit"); FoodWorkOrder.Hunt(WorkOrderCodec.readArea(compound(t,"area")),NpcEntityTypeFilter.of(WorkOrderCodec.readStrings(t,"types",32).toSet(),WorkOrderCodec.readStrings(t,"tags",32).toSet()),int(t,"limit")) }
        else -> throw IllegalArgumentException("unknown food work mode")
    }
}
