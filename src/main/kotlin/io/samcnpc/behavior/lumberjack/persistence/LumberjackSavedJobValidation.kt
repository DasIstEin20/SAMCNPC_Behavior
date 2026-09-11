package io.samcnpc.behavior.lumberjack.persistence

import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.runtime.BehaviorAssignmentStore
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/** Reject invalid identity/obligations before legacy counter migrations can activate a job. */
internal object LumberjackSavedJobValidation {
    private val idPattern = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private val positions = listOf("chest", "workCenter", "target", "trunkBase", "blockedLog", "accessReturnTarget", "miningStance", "chestAccessTarget")
    private val integers = listOf("scanCursor", "pickupTicks", "pickupQuietTicks", "scaffoldRecoveryAttempts", "chestAccessTicks", "chestAccessAttempts", "chestAccessQuietTicks", "climbJumpAttempts", "failedWorkAttempts")
    private val booleans = listOf("resumeWorkAfterDeposit", "initialTrunkTargetPending", "scaffoldMaterialRecovery", "scaffoldMaterialRecoveryPending", "abandonAfterPillarCleanup")

    fun validate(entry: CompoundTag, version: Int) {
        require(entry.hasUUID("npcUuid")) { "missing NPC UUID" }
        id(entry.getString("dimension"))
        require(LumberjackDemoPhase.entries.any { it.name == entry.getString("phase") }) { "unknown job phase" }
        require(entry.contains("chest") && entry.contains("workCenter")) { "missing job chest/work center" }
        for (key in positions) if (entry.contains(key)) position(compound(entry, key))
        for (key in integers) if (entry.contains(key)) require(entry.contains(key, Tag.TAG_INT.toInt())) { "invalid counter type $key" }
        for (key in booleans) if (entry.contains(key)) require(entry.contains(key, Tag.TAG_BYTE.toInt())) { "invalid flag type $key" }
        val packs = list(entry, "previousPacks", Tag.TAG_STRING, 8).map { it.asString }
        require(BehaviorAssignmentStore.validPackIds(packs)) { "invalid previous pack assignments" }
        val woodIds = hashSetOf<String>()
        for (element in list(entry, "initialWood", Tag.TAG_COMPOUND, 32)) {
            val wood = element as CompoundTag
            val item = wood.getString("itemId")
            id(item)
            require(woodIds.add(item)) { "duplicate inventory baseline item" }
            require(wood.contains("count", Tag.TAG_INT.toInt()) && wood.getInt("count") in 1..1_000_000) { "invalid inventory baseline count" }
        }
        positions(entry, "rejectedStances", 4)
        if (!entry.contains("pillarSession")) return
        val pillar = compound(entry, "pillarSession")
        require(pillar.hasUUID("taskId")) { "pillar lost its task identity" }
        require(TemporaryPillarState.entries.any { it.name == pillar.getString("state") }) { "unknown pillar state" }
        position(compound(pillar, "target"))
        id(pillar.getString("materialItemId"))
        for ((key, range) in listOf("originalSlot" to 0..8, "sourceSlot" to 0..35, "activeSlot" to 0..8)) {
            require(pillar.contains(key, Tag.TAG_INT.toInt()) && pillar.getInt(key) in range) { "invalid pillar slot $key" }
        }
        for (key in listOf("estimatedLevels", "retries", "positioningTicks", "cleanupTicks", "expectedMaterialCount")) {
            if (pillar.contains(key)) require(pillar.contains(key, Tag.TAG_INT.toInt())) { "invalid pillar counter $key" }
        }
        if (pillar.contains("placement")) position(compound(pillar, "placement"))
        positions(pillar, "placedPositions", 12)
        if (version >= 19) {
            require(pillar.contains("placedBlocks", Tag.TAG_LIST.toInt())) { "missing support identity list" }
            val placed = list(pillar, "placedPositions", Tag.TAG_COMPOUND, 12).map { value ->
                val position = value as CompoundTag
                Triple(position.getInt("x"), position.getInt("y"), position.getInt("z"))
            }.toSet()
            val identities = hashSetOf<Triple<Int, Int, Int>>()
            for (element in list(pillar, "placedBlocks", Tag.TAG_COMPOUND, 12)) {
                val identity = element as CompoundTag
                position(identity)
                id(identity.getString("blockId"))
                val position = Triple(identity.getInt("x"), identity.getInt("y"), identity.getInt("z"))
                require(position in placed && identities.add(position)) { "support identity is duplicated or has no recorded placement" }
            }
        }
    }

    private fun id(value: String) { require(value.length <= 256 && idPattern.matches(value)) { "invalid namespaced ID" } }
    private fun position(tag: CompoundTag) {
        for (key in listOf("x", "y", "z")) require(tag.contains(key, Tag.TAG_INT.toInt())) { "invalid position component $key" }
        require(tag.getInt("x") in -29_999_984..29_999_984 && tag.getInt("z") in -29_999_984..29_999_984 && tag.getInt("y") in -2048..2048) { "position outside supported world coordinates" }
    }
    private fun positions(tag: CompoundTag, key: String, limit: Int) {
        val entries = list(tag, key, Tag.TAG_COMPOUND, limit)
        val seen = hashSetOf<Triple<Int, Int, Int>>()
        for (value in entries) {
            val position = value as CompoundTag
            position(position)
            require(seen.add(Triple(position.getInt("x"), position.getInt("y"), position.getInt("z")))) { "duplicate $key position" }
        }
    }
    private fun compound(tag: CompoundTag, key: String): CompoundTag {
        require(tag.contains(key, Tag.TAG_COMPOUND.toInt())) { "missing/invalid compound $key" }
        return tag.getCompound(key)
    }
    private fun list(tag: CompoundTag, key: String, type: Byte, limit: Int): ListTag {
        if (!tag.contains(key)) return ListTag()
        val entries = tag.get(key) as? ListTag ?: throw IllegalArgumentException("invalid list $key")
        require(entries.size <= limit && (entries.isEmpty() || entries.elementType == type)) { "oversized/wrong type list $key" }
        return entries
    }
}
