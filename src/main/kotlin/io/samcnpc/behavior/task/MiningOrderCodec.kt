package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*
import io.samcnpc.behavior.task.InventoryWorkCodec.int
import io.samcnpc.behavior.task.InventoryWorkCodec.compound
import io.samcnpc.behavior.task.InventoryWorkCodec.list
import io.samcnpc.behavior.task.InventoryWorkCodec.double
import io.samcnpc.behavior.task.InventoryWorkCodec.position
import io.samcnpc.behavior.task.InventoryWorkCodec.readPosition

internal object MiningOrderCodec {
    fun keys(hasReturn: Boolean) = setOf("work","outputs","destinations","quantity","counting","anchor","radius") + if (hasReturn) setOf("returnTo") else emptySet()
    fun writeDefinition(d: MiningTaskDefinition,t: CompoundTag) {
        t.put("work",write(d.work)); t.put("outputs",ids(d.outputs)); t.put("destinations",LumberjackSupplyCodec.writeChoices(d.destinations))
        t.putInt("quantity",d.quantity); t.putString("counting",d.counting.name); t.put("anchor",position(d.anchor)); t.putDouble("radius",d.travelRadius)
        d.returnTo?.let { t.put("returnTo",position(it)) }
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int) = MiningTaskDefinition(dimension,
        read(compound(t,"work")),readIds(t,"outputs"),requireNotNull(LumberjackSupplyCodec.readChoices(compound(t,"destinations"))),
        int(t,"quantity"),enumValueOf(t.getString("counting")),readPosition(compound(t,"anchor")),double(t,"radius"),
        if (t.contains("returnTo")) readPosition(compound(t,"returnTo")) else null,budget,version)
    private fun write(w: MiningWorkOrder) = CompoundTag().apply {
        putString("method",w.method.name); put("bounds",box(w.area.bounds)); put("exclusions",ListTag().apply { for (b in w.area.exclusions) add(box(b)) })
        put("resources",ids(w.resources)); w.access?.let { put("access",ids(it)) }
        w.tunnel?.let { g -> put("tunnel",CompoundTag().apply {
            put("origin",block(g.origin)); putString("direction",g.direction.name); putInt("width",g.width); putInt("height",g.height); putInt("length",g.length)
        }) }
    }
    private fun read(t: CompoundTag): MiningWorkOrder {
        require(t.allKeys == setOf("method","bounds","exclusions","resources")+listOf("access","tunnel").filter(t::contains)) { "unknown/missing mining work field" }
        val area=WorkArea(readBox(compound(t,"bounds")),list(t,"exclusions",16).map(::readBox))
        val tunnel=if (!t.contains("tunnel")) null else compound(t,"tunnel").let {
            InventoryWorkCodec.keys(it,"origin","direction","width","height","length")
            TunnelGeometry(readBlock(compound(it,"origin")),enumValueOf(it.getString("direction")),int(it,"width"),int(it,"height"),int(it,"length"))
        }
        val value=MiningWorkOrder(area,enumValueOf(t.getString("method")),readIds(t,"resources"),if (t.contains("access")) readIds(t,"access") else null,tunnel)
        require(value.validationProblem() == null) { value.validationProblem().orEmpty() }; return value
    }
    private fun ids(value: WorkResourceIds) = ListTag().apply { for (id in value.values) add(CompoundTag().apply { putString("id",id) }) }
    private fun readIds(t: CompoundTag,key: String) = WorkResourceIds(list(t,key,64).map { InventoryWorkCodec.keys(it,"id"); it.getString("id") })
    private fun box(b: WorkBox) = CompoundTag().apply { put("min",block(b.min)); put("max",block(b.max)) }
    private fun readBox(t: CompoundTag): WorkBox { InventoryWorkCodec.keys(t,"min","max"); return WorkBox(readBlock(compound(t,"min")),readBlock(compound(t,"max"))) }
    internal fun block(p: NpcBlockPosition) = LumberjackTaskCodec.position(p)
    internal fun readBlock(t: CompoundTag): NpcBlockPosition {
        InventoryWorkCodec.keys(t,"x","y","z")
        val p=LumberjackTaskCodec.readPosition(t)
        require(p.x in -29_999_984..29_999_984 && p.z in -29_999_984..29_999_984 && p.y in -2048..2048) { "mining coordinate outside supported world" }; return p
    }
}
