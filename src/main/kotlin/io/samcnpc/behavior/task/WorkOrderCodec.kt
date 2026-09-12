package io.samcnpc.behavior.task

import net.minecraft.nbt.*

/** Shared bounded data encoding for supplied geometry and literal resource IDs. */
internal object WorkOrderCodec {
    fun area(value: WorkArea) = CompoundTag().apply {
        put("bounds",box(value.bounds)); put("exclusions",ListTag().apply { for (entry in value.exclusions) add(box(entry)) })
    }
    fun readArea(tag: CompoundTag): WorkArea {
        InventoryWorkCodec.keys(tag,"bounds","exclusions")
        val value=WorkArea(readBox(InventoryWorkCodec.compound(tag,"bounds")),InventoryWorkCodec.list(tag,"exclusions",16).map(::readBox))
        require(value.validationProblem() == null) { value.validationProblem().orEmpty() }
        return value
    }
    fun strings(values: Collection<String>) = ListTag().apply { for (value in values) add(CompoundTag().apply { putString("id",value) }) }
    fun readStrings(tag: CompoundTag,key: String,limit: Int = 64): List<String> {
        val values=InventoryWorkCodec.list(tag,key,limit).map { InventoryWorkCodec.keys(it,"id"); require(it.contains("id",Tag.TAG_STRING.toInt())); it.getString("id") }
        require(values.distinct().size == values.size) { "duplicate resource ID" }
        require(HarvestResources.validCounts(values.associateWith { 1 })) { "invalid literal resource IDs" }
        return values
    }
    private fun box(b: WorkBox) = CompoundTag().apply { put("min",MiningOrderCodec.block(b.min)); put("max",MiningOrderCodec.block(b.max)) }
    private fun readBox(t: CompoundTag): WorkBox {
        InventoryWorkCodec.keys(t,"min","max")
        return WorkBox(MiningOrderCodec.readBlock(InventoryWorkCodec.compound(t,"min")),MiningOrderCodec.readBlock(InventoryWorkCodec.compound(t,"max")))
    }
}
