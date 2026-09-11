package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

internal object ResourceProgressCodec {
    fun write(progress: ResourceProgress): CompoundTag = CompoundTag().apply {
        putInt("initial", progress.initial)
        putInt("retained", progress.retained)
        putInt("delivered", progress.delivered)
        putInt("containerCount", progress.containerCount)
        putInt("containerSize", progress.containerSize)
        putBoolean("uncertain", progress.uncertain)
        progress.observedRetained?.let { putInt("observedRetained", it) }
        progress.receipt?.let { receipt ->
            put("receipt", CompoundTag().apply {
                putInt("sequence", receipt.sequence)
                putInt("npcBefore", receipt.npcBefore); putInt("npcAfter", receipt.npcAfter)
                putInt("containerBefore", receipt.containerBefore); putInt("containerAfter", receipt.containerAfter)
            })
        }
    }
    fun read(tag: CompoundTag, quantity: Int): ResourceProgress {
        val receipt = if (tag.contains("receipt")) {
            require(tag.contains("receipt", Tag.TAG_COMPOUND.toInt())) { "invalid receipt" }
            val entry = tag.getCompound("receipt")
            TransferReceipt(entry.integer("sequence"), entry.integer("npcBefore"), entry.integer("npcAfter"),
                entry.integer("containerBefore"), entry.integer("containerAfter"))
        } else null
        require(tag.contains("uncertain", Tag.TAG_BYTE.toInt())) { "missing resource certainty" }
        val progress = ResourceProgress(tag.integer("initial"), tag.integer("retained"), tag.integer("delivered"),
            tag.integer("containerCount"), tag.integer("containerSize"), receipt,
            if (tag.contains("observedRetained")) tag.integer("observedRetained") else null, tag.getBoolean("uncertain"))
        require(progress.validate(quantity)) { "inconsistent resource ledger/receipt" }
        return progress
    }
    private fun CompoundTag.integer(key: String): Int {
        require(contains(key, Tag.TAG_INT.toInt())) { "missing resource integer $key" }
        return getInt(key)
    }
}
