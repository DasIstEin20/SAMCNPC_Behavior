package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.*

internal object LumberjackSupplyCodec {
    fun writeChoices(choices: ContainerChoices?) = CompoundTag().apply {
        if (choices != null) {
            putString("preference", choices.preference.name)
            put("positions", ListTag().apply { choices.positions.forEach { add(LumberjackTaskCodec.position(it)) } })
        }
    }
    fun readChoices(tag: CompoundTag): ContainerChoices? {
        if (tag.isEmpty) return null
        require(tag.allKeys == setOf("preference", "positions")) { "invalid wood supply choices" }
        val preference = ContainerPreference.entries.firstOrNull { it.name == tag.getString("preference") } ?: throw IllegalArgumentException("invalid supply preference")
        val positions = list(tag, "positions").map { position(it as CompoundTag) }
        val choices = ContainerChoices(positions, preference); require(choices.validationProblem() == null) { choices.validationProblem().orEmpty() }
        return choices
    }
    fun write(state: LumberjackSupplyState) = CompoundTag().apply {
        state.selected?.let { put("selected", LumberjackTaskCodec.position(it)) }
        put("checkpoints", ListTag().apply { for ((p, checkpoint) in state.checkpoints.toSortedMap(compareBy<NpcBlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z })) add(CompoundTag().apply {
            put("position", LumberjackTaskCodec.position(p)); putString("block", checkpoint.blockId); putInt("size", checkpoint.size)
            put("counts", LumberjackTaskCodec.counts(checkpoint.counts))
        }) })
    }
    fun read(tag: CompoundTag, choices: ContainerChoices?): LumberjackSupplyState {
        require(tag.allKeys == setOf("checkpoints") + if (tag.contains("selected")) setOf("selected") else emptySet()) { "invalid wood supply state" }
        val state = LumberjackSupplyState(if (tag.contains("selected")) position(tag.getCompound("selected")) else null)
        val allowed = choices?.positions.orEmpty()
        require(state.selected == null || state.selected in allowed) { "selected wood supply is not authorized" }
        for (element in list(tag, "checkpoints")) {
            val entry = element as CompoundTag
            require(entry.allKeys == setOf("position", "block", "size", "counts") && entry.contains("size", Tag.TAG_INT.toInt())) { "invalid supply checkpoint" }
            val position = position(entry.getCompound("position")); val block = entry.getString("block"); val size = entry.getInt("size")
            require(position in allowed && size in 1..64 && HarvestResources.validCounts(mapOf(block to 1))) { "invalid/unassigned supply checkpoint" }
            require(state.checkpoints.put(position, LumberjackSupplyCheckpoint(block, size, LumberjackTaskCodec.readCounts(entry, "counts"))) == null) { "duplicate supply checkpoint" }
        }
        require(state.selected == null || state.selected in state.checkpoints) { "selected supply has no observed identity" }
        return state
    }
    private fun position(tag: CompoundTag): NpcBlockPosition {
        require(tag.allKeys == setOf("x", "y", "z")) { "invalid supply position" }
        val p = LumberjackTaskCodec.readPosition(tag)
        require(p.x in -29_999_984..29_999_984 && p.z in -29_999_984..29_999_984 && p.y in -2048..2048) { "unsupported supply coordinates" }
        return p
    }
    private fun list(tag: CompoundTag, key: String): ListTag {
        val value = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing supply list $key")
        require(value.size <= 8 && (value.isEmpty() || value.elementType == Tag.TAG_COMPOUND)) { "invalid/oversized supply list $key" }; return value
    }
}
