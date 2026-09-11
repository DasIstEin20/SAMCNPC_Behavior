package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/** Strict bounded combat values; no Core execution/route or live damage identity is serialized. */
internal object TaskCombatCodec {
    val definitionKeys = setOf("target", "anchor", "leash", "allowPlayers")

    fun writeDefinition(definition: AttackTaskDefinition, tag: CompoundTag) {
        tag.putUUID("target", definition.targetUuid)
        tag.put("anchor", CompoundTag().apply { putDouble("x", definition.anchor.x); putDouble("y", definition.anchor.y); putDouble("z", definition.anchor.z) })
        tag.putDouble("leash", definition.leash)
        tag.putBoolean("allowPlayers", definition.allowPlayers)
    }
    fun readDefinition(tag: CompoundTag, dimension: String, budget: TaskBudget, version: Int): AttackTaskDefinition {
        require(tag.hasUUID("target") && tag.contains("anchor", Tag.TAG_COMPOUND.toInt())) { "missing attack identity/anchor" }
        val anchor = tag.getCompound("anchor")
        require(anchor.allKeys == setOf("x", "y", "z")) { "unknown/missing anchor coordinates" }
        return AttackTaskDefinition(dimension, tag.getUUID("target"), NpcPosition(anchor.double("x"), anchor.double("y"), anchor.double("z")),
            tag.double("leash"), tag.boolean("allowPlayers"), budget, version)
    }

    fun writeReaction(state: TaskReactionState) = CompoundTag().apply {
        putString("mode", state.policy.mode.name)
        putDouble("leash", state.policy.leash)
        putInt("duration", state.policy.durationTicks)
        putInt("cooldown", state.policy.cooldownTicks)
        putBoolean("allowPlayers", state.policy.allowPlayers)
        putInt("remainingCooldown", state.cooldownRemaining)
    }
    fun readReaction(tag: CompoundTag): TaskReactionState {
        require(tag.allKeys == setOf("mode", "leash", "duration", "cooldown", "allowPlayers", "remainingCooldown")) { "unknown/missing reaction parameter" }
        val mode = TaskReactionMode.entries.firstOrNull { it.name == tag.getString("mode") } ?: throw IllegalArgumentException("unknown reaction mode")
        val policy = TaskReactionPolicy(mode, tag.double("leash"), tag.int("duration"), tag.int("cooldown"), tag.boolean("allowPlayers"))
        require(policy.validationProblem() == null) { policy.validationProblem().orEmpty() }
        val remaining = tag.int("remainingCooldown")
        require(remaining in 0..200) { "invalid remaining reaction cooldown" }
        return TaskReactionState(policy, remaining)
    }

    fun writeOutcome(result: TaskCombatOutcome) = CompoundTag().apply {
        putUUID("target", result.targetUuid); putString("status", result.status.name); putString("reason", result.reason.name)
        putInt("kills", result.confirmedKills); putString("detail", result.detail)
    }
    fun readOutcome(tag: CompoundTag): TaskCombatOutcome {
        require(tag.allKeys == setOf("target", "status", "reason", "kills", "detail") && tag.hasUUID("target")) { "unknown/missing combat result" }
        val status = TaskStatus.entries.firstOrNull { it.name == tag.getString("status") } ?: throw IllegalArgumentException("unknown combat status")
        val reason = TaskReason.entries.firstOrNull { it.name == tag.getString("reason") } ?: throw IllegalArgumentException("unknown combat result reason")
        val kills = tag.int("kills")
        val detail = tag.getString("detail")
        require(detail.length <= TaskRecord.MAX_DETAIL_LENGTH && kills in 0..1) { "oversized combat result" }
        val allowed = when (status) {
            TaskStatus.COMPLETED -> setOf(TaskReason.TARGET_DEFEATED)
            TaskStatus.CANCELLED -> setOf(TaskReason.TARGET_ENDED, TaskReason.TARGET_UNAVAILABLE, TaskReason.LEASH_REACHED, TaskReason.PERMISSION_CHANGED, TaskReason.COMBAT_TIME_LIMIT, TaskReason.USER_CANCELLED)
            TaskStatus.FAILED -> setOf(TaskReason.COMBAT_NO_PROGRESS)
            else -> emptySet()
        }
        require(reason in allowed && (kills == 1) == (reason == TaskReason.TARGET_DEFEATED)) { "inconsistent combat result" }
        return TaskCombatOutcome(tag.getUUID("target"), status, reason, kills, detail)
    }

    private fun CompoundTag.int(key: String): Int { require(contains(key, Tag.TAG_INT.toInt())) { "missing integer $key" }; return getInt(key) }
    private fun CompoundTag.double(key: String): Double { require(contains(key, Tag.TAG_DOUBLE.toInt())) { "missing double $key" }; return getDouble(key) }
    private fun CompoundTag.boolean(key: String): Boolean {
        require(contains(key, Tag.TAG_BYTE.toInt()) && getByte(key).toInt() in 0..1) { "invalid boolean $key" }
        return getBoolean(key)
    }
}
