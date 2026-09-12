package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag

/** Immutable archive of actual food origin, source, recipient, bush and hunting facts. */
internal class FoodObjectiveEvidence private constructor(private val encoded: CompoundTag) {
    fun write(): CompoundTag = encoded.copy()
    fun read(definition: FoodTaskDefinition): FoodTaskState = FoodStateCodec.read(encoded,definition)
    override fun equals(other: Any?) = other is FoodObjectiveEvidence && encoded == other.encoded
    override fun hashCode() = encoded.hashCode()
    companion object {
        fun capture(state: FoodTaskState) = FoodObjectiveEvidence(FoodStateCodec.write(state))
        fun restore(tag: CompoundTag,definition: FoodTaskDefinition): FoodObjectiveEvidence {
            FoodStateCodec.read(tag,definition)
            return FoodObjectiveEvidence(tag.copy())
        }
    }
}
