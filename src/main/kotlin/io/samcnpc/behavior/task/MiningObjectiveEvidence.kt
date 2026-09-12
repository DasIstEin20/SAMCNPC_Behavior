package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag

/** Frozen, structurally comparable report evidence; callers never receive its mutable NBT. */
internal class MiningObjectiveEvidence private constructor(private val encoded: CompoundTag) {
    fun write(): CompoundTag = encoded.copy()
    fun read(definition: MiningTaskDefinition): MiningTaskState = MiningStateCodec.read(encoded,definition)
    override fun equals(other: Any?): Boolean = other is MiningObjectiveEvidence && encoded == other.encoded
    override fun hashCode(): Int = encoded.hashCode()
    companion object {
        fun capture(state: MiningTaskState) = MiningObjectiveEvidence(MiningStateCodec.write(state))
        fun restore(tag: CompoundTag,definition: MiningTaskDefinition): MiningObjectiveEvidence {
            MiningStateCodec.read(tag,definition)
            return MiningObjectiveEvidence(tag.copy())
        }
    }
}
