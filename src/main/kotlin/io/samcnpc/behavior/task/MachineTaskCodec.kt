package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal object MachineTaskCodec {
    fun definitionKeys(returning: Boolean) = setOf("feeds","output","anchor","travelRadius","pollTicks","noProgressTicks") + if (returning) setOf("returnTo") else emptySet()
    fun writeDefinition(d: MachineTaskDefinition,t: CompoundTag) {
        val feeds=ListTag(); for (port in d.feeds.ports) feeds.add(writePort(port)); t.put("feeds",feeds)
        t.put("output",writePort(d.output));t.put("anchor",InventoryWorkCodec.position(d.anchor))
        t.putDouble("travelRadius",d.travelRadius);t.putInt("pollTicks",d.pollTicks);t.putInt("noProgressTicks",d.noProgressTicks)
        d.returnTo?.let { t.put("returnTo",InventoryWorkCodec.position(it)) }
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int) = MachineTaskDefinition(dimension,
        MachineFeeds(InventoryWorkCodec.list(t,"feeds",4).map { readPort(it,dimension) }),readPort(InventoryWorkCodec.compound(t,"output"),dimension),
        InventoryWorkCodec.readPosition(InventoryWorkCodec.compound(t,"anchor")),InventoryWorkCodec.double(t,"travelRadius"),
        if (t.contains("returnTo")) InventoryWorkCodec.readPosition(InventoryWorkCodec.compound(t,"returnTo")) else null,
        InventoryWorkCodec.int(t,"pollTicks"),InventoryWorkCodec.int(t,"noProgressTicks"),budget,version)
    private fun writePort(port: MachinePort): CompoundTag {
        val t=CompoundTag();val p=port.endpoint.position
        t.putInt("x",p.x);t.putInt("y",p.y);t.putInt("z",p.z);t.putString("side",port.endpoint.side?.name ?: "UNSIDED")
        t.putInt("slot",port.slot);t.putString("item",port.itemId);t.putInt("quantity",port.quantity);return t
    }
    private fun readPort(t: CompoundTag,dimension: String): MachinePort {
        InventoryWorkCodec.keys(t,"x","y","z","side","slot","item","quantity")
        val side=t.getString("side")
        val face=if (side == "UNSIDED") null else NpcBlockFace.entries.firstOrNull { it.name == side } ?: throw IllegalArgumentException("unknown machine face")
        return MachinePort(NpcContainerEndpoint(dimension,NpcBlockPosition(InventoryWorkCodec.int(t,"x"),InventoryWorkCodec.int(t,"y"),InventoryWorkCodec.int(t,"z")),face),
            InventoryWorkCodec.int(t,"slot"),t.getString("item"),InventoryWorkCodec.int(t,"quantity"))
    }
    fun write(s: MachineTaskState): CompoundTag {
        val t=CompoundTag();t.put("resources",InventoryLedgerCodec.write(s.resources));t.putIntArray("supplied",s.supplied)
        t.putInt("collected",s.collected);t.putInt("cursor",s.cursor);t.putInt("pollRemaining",s.pollRemaining);t.putInt("idleTicks",s.idleTicks)
        t.putString("phase",s.phase.name);t.putInt("transfers",s.transfers)
        val shapes=ListTag();for (shape in s.shapes) { val row=CompoundTag();row.putString("block",shape.blockId);row.putInt("slots",shape.slots);shapes.add(row) };t.put("shapes",shapes)
        return t
    }
    fun read(t: CompoundTag,d: MachineTaskDefinition): MachineTaskState {
        InventoryWorkCodec.keys(t,"resources","supplied","collected","cursor","pollRemaining","idleTicks","phase","transfers","shapes")
        require(t.contains("supplied",Tag.TAG_INT_ARRAY.toInt())) { "missing machine input counters" }
        val supplied=t.getIntArray("supplied");require(supplied.size in 1..4) { "oversized machine input counters" }
        val shapes=InventoryWorkCodec.list(t,"shapes",5).map { row ->
            InventoryWorkCodec.keys(row,"block","slots");MachinePortShape(row.getString("block"),InventoryWorkCodec.int(row,"slots"))
        }
        val phase=MachinePhase.entries.firstOrNull { it.name == t.getString("phase") } ?: throw IllegalArgumentException("unknown machine phase")
        val state=MachineTaskState(InventoryLedgerCodec.read(InventoryWorkCodec.compound(t,"resources")),supplied,shapes,
            InventoryWorkCodec.int(t,"collected"),InventoryWorkCodec.int(t,"cursor"),InventoryWorkCodec.int(t,"pollRemaining"),
            InventoryWorkCodec.int(t,"idleTicks"),phase,InventoryWorkCodec.int(t,"transfers"))
        val problem=state.validationProblem(d);require(problem == null) { problem.orEmpty() };return state
    }
}
