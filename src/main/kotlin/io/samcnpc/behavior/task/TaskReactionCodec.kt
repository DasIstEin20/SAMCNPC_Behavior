package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.*

internal object TaskReactionCodec {
    fun write(state: TaskReactionState) = CompoundTag().apply {
        val policy = state.policy
        putString("mode", policy.mode.name); putDouble("leash", policy.leash)
        putInt("duration", policy.durationTicks); putInt("cooldown", policy.cooldownTicks)
        putBoolean("allowPlayers", policy.allowPlayers); putInt("remainingCooldown", state.cooldownRemaining)
        put("tactics", CombatTacticsCodec.write(policy.tactics))
        policy.anchor?.let { p -> put("anchor", CompoundTag().apply { putDouble("x", p.x); putDouble("y", p.y); putDouble("z", p.z) }) }
        policy.subjectUuid?.let { putUUID("subject", it) }
        if (policy.mode == TaskReactionMode.AREA) { put("types", strings(policy.filter.typeIds)); put("tags", strings(policy.filter.tagIds)) }
        state.consumedProtectionHit?.let { hit -> put("consumedProtectionHit", CompoundTag().apply {
            putUUID("subject", hit.subject); putUUID("attacker", hit.attacker); putLong("gameTime", hit.gameTime)
        }) }
        state.activeFrame?.let { putUUID("activeFrame", it) }
    }

    fun read(tag: CompoundTag, sourceVersion: Int): TaskReactionState {
        val mode = TaskReactionMode.entries.firstOrNull { it.name == tag.getString("mode") } ?: throw IllegalArgumentException("unknown reaction mode")
        require(sourceVersion >= 5 || mode == TaskReactionMode.PASSIVE || mode == TaskReactionMode.RETALIATE) { "old task contains a future reaction mode" }
        val protection = mode == TaskReactionMode.PROTECT_SUMMONER || mode == TaskReactionMode.PROTECT_UNIT
        val keys = mutableSetOf("mode", "leash", "duration", "cooldown", "allowPlayers", "remainingCooldown")
        if (sourceVersion >= 5) {
            keys.add("tactics")
            if (protection || mode == TaskReactionMode.AREA) keys.add("anchor")
            if (protection) keys.add("subject")
            if (mode == TaskReactionMode.AREA) keys.addAll(setOf("types", "tags"))
            if (tag.contains("consumedProtectionHit")) keys.add("consumedProtectionHit")
            if (tag.contains("activeFrame")) keys.add("activeFrame")
        }
        require(tag.allKeys == keys) { "unknown/missing reaction parameter" }
        val anchor = if (keys.contains("anchor")) {
            val p = tag.getCompound("anchor"); require(p.allKeys == setOf("x", "y", "z")) { "invalid reaction anchor" }
            NpcPosition(p.double("x"), p.double("y"), p.double("z"))
        } else null
        val subject = if (protection) { require(tag.hasUUID("subject")) { "invalid protected subject UUID" }; tag.getUUID("subject") } else null
        val filter = if (mode == TaskReactionMode.AREA) NpcEntityTypeFilter.of(readStrings(tag, "types"), readStrings(tag, "tags")) else NpcEntityTypeFilter.ANY
        val policy = TaskReactionPolicy(mode, tag.double("leash"), tag.int("duration"), tag.int("cooldown"), tag.boolean("allowPlayers"),
            if (sourceVersion >= 5) CombatTacticsCodec.read(tag.getCompound("tactics")) else CombatTactics.LEGACY, anchor, subject, filter)
        require(policy.validationProblem() == null) { policy.validationProblem().orEmpty() }
        val remaining = tag.int("remainingCooldown"); require(remaining in 0..200) { "invalid remaining reaction cooldown" }
        val hit = if (tag.contains("consumedProtectionHit")) {
            val saved = tag.getCompound("consumedProtectionHit")
            require(protection && saved.allKeys == setOf("subject", "attacker", "gameTime") && saved.hasUUID("subject") && saved.hasUUID("attacker") &&
                saved.contains("gameTime", Tag.TAG_LONG.toInt()) && saved.getLong("gameTime") >= 0 && saved.getUUID("subject") == subject) { "invalid protected hit watermark" }
            TaskProtectionHit(saved.getUUID("subject"), saved.getUUID("attacker"), saved.getLong("gameTime"))
        } else null
        val frame = if (tag.contains("activeFrame")) { require(tag.hasUUID("activeFrame")); tag.getUUID("activeFrame") } else null
        require(frame == null || mode != TaskReactionMode.PASSIVE) { "passive policy retains an active reaction" }
        return TaskReactionState(policy, remaining, hit, frame)
    }

    private fun strings(values: Set<String>) = ListTag().apply { values.sorted().forEach { add(StringTag.valueOf(it)) } }
    private fun readStrings(tag: CompoundTag, key: String): Set<String> {
        val values = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing reaction filter $key")
        require(values.size <= 32 && (values.isEmpty() || values.elementType == Tag.TAG_STRING)) { "invalid bounded reaction filter" }
        val strings = values.map { it.asString }
        require(strings.distinct().size == strings.size) { "duplicate reaction filter" }
        return strings.toSet()
    }
    private fun CompoundTag.int(key: String): Int { require(contains(key, Tag.TAG_INT.toInt())) { "missing integer $key" }; return getInt(key) }
    private fun CompoundTag.double(key: String): Double { require(contains(key, Tag.TAG_DOUBLE.toInt())) { "missing double $key" }; return getDouble(key) }
    private fun CompoundTag.boolean(key: String): Boolean {
        require(contains(key, Tag.TAG_BYTE.toInt()) && getByte(key).toInt() in 0..1) { "invalid boolean $key" }; return getBoolean(key)
    }
}
