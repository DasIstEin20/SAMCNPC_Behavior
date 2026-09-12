package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal object ExplorerTaskCodec {
    val definitionKeys = setOf("anchor","radius","cellStep","maxCells","verticalRange","chunkBudget","heading")
    fun writeDefinition(d: ExplorerTaskDefinition,t: CompoundTag) {
        t.put("anchor",InventoryWorkCodec.position(d.anchor))
        t.putInt("radius",d.radius);t.putInt("cellStep",d.cellStep);t.putInt("maxCells",d.maxCells)
        t.putInt("verticalRange",d.verticalRange);t.putInt("chunkBudget",d.chunkBudget);t.putInt("heading",d.heading)
    }
    fun readDefinition(t: CompoundTag,dimension: String,budget: TaskBudget,version: Int) = ExplorerTaskDefinition(
        dimension,InventoryWorkCodec.readPosition(InventoryWorkCodec.compound(t,"anchor")),
        InventoryWorkCodec.int(t,"radius"),InventoryWorkCodec.int(t,"cellStep"),InventoryWorkCodec.int(t,"maxCells"),
        InventoryWorkCodec.int(t,"verticalRange"),InventoryWorkCodec.int(t,"chunkBudget"),InventoryWorkCodec.int(t,"heading"),budget,version,
    )
    fun write(s: ExplorerTaskState): CompoundTag {
        val t=CompoundTag();val nodes=ListTag()
        for (node in s.nodes) nodes.add(writeNode(node))
        t.put("nodes",nodes);t.putInt("cursor",s.cursor);t.putString("phase",s.phase.name)
        s.pending?.let { t.put("pending",writeNode(it)) }
        s.stop?.let { t.putString("stop",it.name) }
        t.putInt("rejectedLegs",s.rejectedLegs);t.putString("lastFailure",s.lastFailure)
        return t
    }
    fun read(t: CompoundTag,d: ExplorerTaskDefinition): ExplorerTaskState {
        val keys=setOf("nodes","cursor","phase","rejectedLegs","lastFailure")+setOf("pending","stop").filter { t.contains(it) }
        require(t.allKeys == keys) { "unknown/missing explorer state field" }
        val phase=ExplorerPhase.entries.firstOrNull { it.name == text(t,"phase",32) }
            ?: throw IllegalArgumentException("unknown explorer phase")
        val stop=if (t.contains("stop")) ExplorerStop.entries.firstOrNull { it.name == text(t,"stop",32) }
            ?: throw IllegalArgumentException("unknown explorer stop") else null
        val nodes=InventoryWorkCodec.list(t,"nodes",64).map(::readNode).toMutableList()
        val state=ExplorerTaskState(nodes,InventoryWorkCodec.int(t,"cursor"),phase,
            if (t.contains("pending")) readNode(InventoryWorkCodec.compound(t,"pending")) else null,
            stop,InventoryWorkCodec.int(t,"rejectedLegs"),text(t,"lastFailure",256))
        val problem=state.validationProblem(d)
        require(problem == null) { problem.orEmpty() }
        return state
    }
    private fun writeNode(n: ExplorerNode) = CompoundTag().apply {
        putInt("x",n.x);putInt("z",n.z);putDouble("y",n.y);putInt("parent",n.parent);putInt("tried",n.tried)
    }
    private fun readNode(t: CompoundTag): ExplorerNode {
        InventoryWorkCodec.keys(t,"x","z","y","parent","tried")
        return ExplorerNode(InventoryWorkCodec.int(t,"x"),InventoryWorkCodec.int(t,"z"),InventoryWorkCodec.double(t,"y"),
            InventoryWorkCodec.int(t,"parent"),InventoryWorkCodec.int(t,"tried"))
    }
    private fun text(t: CompoundTag,key: String,limit: Int): String {
        require(t.contains(key,Tag.TAG_STRING.toInt())) { "missing explorer text $key" }
        val value=t.getString(key);require(value.length <= limit) { "oversized explorer text $key" };return value
    }
}
