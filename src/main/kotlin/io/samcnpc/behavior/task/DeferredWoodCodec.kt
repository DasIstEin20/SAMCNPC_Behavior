package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.LumberjackDeferredWork
import io.samcnpc.behavior.lumberjack.model.LumberjackDeferredTarget
import net.minecraft.nbt.*

internal object DeferredWoodCodec {
    fun write(entries: List<LumberjackDeferredTarget>) = ListTag().apply { entries.forEach { entry -> add(CompoundTag().apply {
        put("target", LumberjackTaskCodec.position(entry.target)); putString("targetBlock", entry.targetBlockId)
        put("obstruction", LumberjackTaskCodec.position(entry.obstruction)); putString("obstructionBlock", entry.obstructionBlockId)
        putInt("failedAttempts", entry.failedAttempts); putBoolean("revisited", entry.revisited)
    }) } }
    fun read(tag: Tag?, definition: LumberjackTaskDefinition): List<LumberjackDeferredTarget> {
        val list = tag as? ListTag ?: throw IllegalArgumentException("invalid deferred wood list")
        require(list.size <= LumberjackDeferredWork.MAX_TARGETS && (list.isEmpty() || list.elementType == Tag.TAG_COMPOUND)) { "oversized/invalid deferred wood list" }
        val entries = list.map { element ->
            val value = element as CompoundTag
            require(value.allKeys == setOf("target", "targetBlock", "obstruction", "obstructionBlock", "failedAttempts", "revisited")) { "unknown/missing deferred wood field" }
            val target = LumberjackTaskCodec.readPosition(value.getCompound("target")); val obstruction = LumberjackTaskCodec.readPosition(value.getCompound("obstruction"))
            val targetBlock = value.getString("targetBlock"); val obstructionBlock = value.getString("obstructionBlock")
            require(definition.area.contains(target) && definition.wood.matches(targetBlock) && target != obstruction &&
                HarvestResources.validCounts(mapOf(targetBlock to 1, obstructionBlock to 1))) { "invalid deferred wood identity/permission" }
            require(obstruction.x in -29_999_984..29_999_984 && obstruction.z in -29_999_984..29_999_984 && obstruction.y in -2048..2048) { "invalid obstruction coordinates" }
            require(value.contains("failedAttempts", Tag.TAG_INT.toInt()) && value.getInt("failedAttempts") in 1..3 &&
                value.contains("revisited", Tag.TAG_BYTE.toInt()) && value.getByte("revisited").toInt() in 0..1) { "invalid deferred wood attempt state" }
            LumberjackDeferredTarget(target, targetBlock, obstruction, obstructionBlock, value.getInt("failedAttempts"), value.getBoolean("revisited"))
        }
        require(entries.map { it.target }.distinct().size == entries.size) { "duplicate deferred wood target" }
        return entries
    }
}
