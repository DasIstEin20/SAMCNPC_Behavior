package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag

/** Frozen actual planting stage, including its definition when composed after wood work. */
internal class PlantingObjectiveEvidence private constructor(private val encoded: CompoundTag) {
    fun write(): CompoundTag=encoded.copy()
    fun definition(): PlantingTaskDefinition=PlantingOrderCodec.readNested(InventoryWorkCodec.compound(encoded,"definition"))
    fun read(): PlantingTaskState=PlantingStateCodec.read(InventoryWorkCodec.compound(encoded,"state"),definition())
    override fun equals(other: Any?)=other is PlantingObjectiveEvidence && encoded == other.encoded
    override fun hashCode()=encoded.hashCode()
    companion object {
        fun capture(state: PlantingTaskState,definition: PlantingTaskDefinition)=PlantingObjectiveEvidence(CompoundTag().apply { put("definition",TaskCodec.writeDefinition(definition)); put("state",PlantingStateCodec.write(state)) })
        fun restore(tag: CompoundTag): PlantingObjectiveEvidence {
            InventoryWorkCodec.keys(tag,"definition","state")
            val evidence=PlantingObjectiveEvidence(tag.copy()); evidence.read(); return evidence
        }
    }
}
