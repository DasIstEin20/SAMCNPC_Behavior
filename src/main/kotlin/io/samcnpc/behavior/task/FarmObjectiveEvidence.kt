package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag

internal class FarmObjectiveEvidence private constructor(private val encoded: CompoundTag) {
    fun write(): CompoundTag = encoded.copy()
    fun read(definition: FarmTaskDefinition): FarmTaskState = FarmStateCodec.read(encoded,definition)
    override fun equals(other: Any?) = other is FarmObjectiveEvidence && encoded == other.encoded
    override fun hashCode() = encoded.hashCode()
    companion object {
        fun capture(state: FarmTaskState) = FarmObjectiveEvidence(FarmStateCodec.write(state))
        fun restore(tag: CompoundTag,definition: FarmTaskDefinition): FarmObjectiveEvidence {
            FarmStateCodec.read(tag,definition)
            return FarmObjectiveEvidence(tag.copy())
        }
    }
}
