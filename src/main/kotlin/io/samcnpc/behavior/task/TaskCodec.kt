package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorAssignmentStore
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag

/** Strict bounded codec; no active Core action, route, claim or execution generation is written. */
internal object TaskCodec {
    fun write(record: TaskRecord): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("npcUuid", record.npcUuid)
        tag.putUUID("taskId", record.id)
        tag.putString("status", record.status.name)
        tag.putString("reason", record.reason.name)
        tag.putString("detail", record.detail)
        tag.putInt("totalFailures", record.totalFailures)
        tag.putInt("completedInterruptions", record.completedInterruptions)
        tag.put("reaction", TaskCombatCodec.writeReaction(record.reaction))
        record.lastCombat?.let { tag.put("lastCombat", TaskCombatCodec.writeOutcome(it)) }
        val previous = ListTag()
        for (id in record.previousPacks) previous.add(StringTag.valueOf(id))
        tag.put("previousPacks", previous)
        val frames = ListTag()
        for (frame in record.frames) {
            val entry = CompoundTag()
            entry.putUUID("frameId", frame.id)
            entry.put("definition", writeDefinition(frame.definition))
            entry.putInt("remainingTicks", frame.remainingTicks)
            entry.putInt("failures", frame.failures)
            entry.putInt("waitTicks", frame.waitTicks)
            entry.putString("reason", frame.reason.name)
            frame.resources?.let { entry.put("resources", ResourceProgressCodec.write(it)) }
            frame.lumberjack?.let { entry.put("lumberjack", LumberjackTaskCodec.write(it)) }
            frames.add(entry)
        }
        tag.put("frames", frames)
        record.reconciledPosition?.let { tag.put("observedPosition", position(it)) }
        return tag
    }

    fun read(tag: CompoundTag, sourceVersion: Int = 4): TaskRecord {
        require(tag.hasUUID("npcUuid") && tag.hasUUID("taskId")) { "missing NPC/task UUID" }
        val status = TaskStatus.entries.firstOrNull { it.name == tag.getString("status") } ?: errorValue("unknown task status")
        val reason = reason(tag)
        val reaction = if (tag.contains("reaction")) TaskCombatCodec.readReaction(compound(tag, "reaction")) else {
            require(sourceVersion < 4) { "v4 task is missing reaction policy" }
            TaskReactionState()
        }
        val lastCombat = if (tag.contains("lastCombat")) TaskCombatCodec.readOutcome(compound(tag, "lastCombat")) else null
        val detail = tag.getString("detail")
        require(detail.length <= TaskRecord.MAX_DETAIL_LENGTH) { "oversized task detail" }
        val previous = list(tag, "previousPacks", Tag.TAG_STRING, 8).map { it.asString }
        require(BehaviorAssignmentStore.validPackIds(previous)) { "invalid previous assignments" }
        val frames = list(tag, "frames", Tag.TAG_COMPOUND, TaskRecord.MAX_FRAMES).map { element ->
            val entry = element as CompoundTag
            require(entry.hasUUID("frameId")) { "missing frame UUID" }
            val definition = readDefinition(compound(entry, "definition"))
            val remaining = entry.int("remainingTicks")
            val failures = entry.int("failures")
            val wait = entry.int("waitTicks")
            require(remaining in 0..definition.budget.ticks && failures in 0..definition.budget.attempts && wait in 0..200) { "invalid task frame budget" }
            val resources = when (definition) {
                is NavigateTaskDefinition, is LumberjackTaskDefinition, is AttackTaskDefinition -> { require(!entry.contains("resources")) { "navigation has unexpected resources" }; null }
                is DeliveryTaskDefinition -> ResourceProgressCodec.read(compound(entry, "resources"), definition.quantity)
            }
            val lumberjack = if (definition is LumberjackTaskDefinition) {
                LumberjackTaskCodec.read(compound(entry, "lumberjack"), tag.getUUID("npcUuid"), definition)
            } else { require(!entry.contains("lumberjack")) { "unexpected wood work state" }; null }
            TaskFrame(entry.getUUID("frameId"), definition, remaining, failures, wait, reason(entry), resources, lumberjack)
        }
        require(frames.isNotEmpty() && frames.map { it.id }.distinct().size == frames.size) { "missing/duplicate frame identity" }
        require(frames.all { it.definition.dimensionId == frames.first().definition.dimensionId }) { "mixed task dimensions" }
        require(status.terminal || frames.all { it.remainingTicks > 0 && it.failures < it.definition.budget.attempts }) { "active task has exhausted its budget" }
        val reasons = when (status) {
            TaskStatus.RUNNING -> setOf(TaskReason.ASSIGNED, TaskReason.USER_RESUMED, TaskReason.INTERRUPTED, TaskReason.RESUMED, TaskReason.CHANNEL_UNAVAILABLE)
            TaskStatus.WAITING -> setOf(TaskReason.DESTINATION_UNAVAILABLE, TaskReason.NO_PROGRESS, TaskReason.STORAGE_FULL, TaskReason.USER_RESUMED, TaskReason.RESUMED)
            TaskStatus.PAUSED -> setOf(TaskReason.USER_PAUSED)
            TaskStatus.COMPLETED -> setOf(TaskReason.ARRIVED, TaskReason.DELIVERED, TaskReason.TARGET_DEFEATED)
            TaskStatus.CANCELLED -> setOf(TaskReason.USER_CANCELLED, TaskReason.ASSIGNMENT_CHANGED, TaskReason.NPC_REMOVED,
                TaskReason.TARGET_ENDED, TaskReason.TARGET_UNAVAILABLE, TaskReason.LEASH_REACHED, TaskReason.PERMISSION_CHANGED, TaskReason.COMBAT_TIME_LIMIT)
            TaskStatus.FAILED -> setOf(TaskReason.RETRY_LIMIT, TaskReason.TIME_LIMIT, TaskReason.DIMENSION_CHANGED, TaskReason.STATE_MISMATCH, TaskReason.MISSING_RESOURCE, TaskReason.WORK_FAILED, TaskReason.COMBAT_NO_PROGRESS)
        }
        require(reason in reasons) { "task reason is inconsistent with its status" }
        require(frames.drop(1).all { it.definition is NavigateTaskDefinition || it.definition is AttackTaskDefinition }) { "unsupported resource interruption" }
        require(frames.count { it.definition is AttackTaskDefinition } <= 1) { "nested combat frames are forbidden" }
        require(status.terminal || frames.none { it.resources?.uncertain == true || it.lumberjack?.resources?.uncertain == true }) { "active task has uncertain resources" }
        if (status == TaskStatus.COMPLETED) {
            when (val primary = frames.first().definition) {
                is AttackTaskDefinition -> require(reason == TaskReason.TARGET_DEFEATED && lastCombat != null && lastCombat.targetUuid == primary.targetUuid &&
                    lastCombat.status == TaskStatus.COMPLETED && lastCombat.confirmedKills == 1) { "attack completion lacks a confirmed kill" }
                is LumberjackTaskDefinition -> require(reason == TaskReason.DELIVERED &&
                    (frames.first().lumberjack?.resources?.delivered(primary.wood) ?: 0) >= primary.quantity) { "wood completion lacks confirmed net quantity" }
                is NavigateTaskDefinition -> require(reason == TaskReason.ARRIVED) { "navigation completion reason differs" }
                is DeliveryTaskDefinition -> require(reason == TaskReason.DELIVERED && frames.first().resources?.delivered == primary.quantity) { "delivery completion lacks confirmed quantity" }
            }
        }
        require(status != TaskStatus.COMPLETED || frames.size == 1) { "completed task retains an unfinished interruption" }
        if (status == TaskStatus.WAITING) require(frames.last().waitTicks > 0) { "waiting task has no wake deadline" }
        if (status == TaskStatus.RUNNING) require(frames.last().waitTicks == 0) { "running task has an unresolved wait" }
        val failures = tag.int("totalFailures")
        val completed = tag.int("completedInterruptions")
        require(failures in frames.sumOf { it.failures }..264 && completed in 0..32) { "invalid accumulated task counters" }
        require(completed + frames.size - 1 <= 32) { "interruption budget exceeded" }
        val observed = if (tag.contains("observedPosition")) readPosition(compound(tag, "observedPosition")) else null
        return TaskRecord(tag.getUUID("npcUuid"), tag.getUUID("taskId"), frames, previous,
            status, reason, detail, observed, failures, completed, reaction, lastCombat)
    }

    private fun writeDefinition(definition: TaskDefinition): CompoundTag {
        val tag = CompoundTag()
        tag.putString("id", definition.operationId)
        tag.putInt("version", definition.version)
        tag.putString("dimension", definition.dimensionId)
        tag.putInt("duration", definition.budget.ticks)
        tag.putInt("attempts", definition.budget.attempts)
        tag.putInt("backoff", definition.budget.backoffTicks)
        when (definition) {
            is AttackTaskDefinition -> TaskCombatCodec.writeDefinition(definition, tag)
            is LumberjackTaskDefinition -> LumberjackTaskCodec.writeDefinition(definition, tag)
            is NavigateTaskDefinition -> {
                tag.put("destination", position(definition.destination))
                tag.putFloat("speed", definition.speed)
                tag.putDouble("arrivalDistance", definition.arrivalDistance)
            }
            is DeliveryTaskDefinition -> {
                tag.put("container", CompoundTag().apply {
                    putInt("x", definition.destination.x); putInt("y", definition.destination.y); putInt("z", definition.destination.z)
                })
                tag.putString("item", definition.itemId)
                tag.putInt("quantity", definition.quantity)
                tag.putInt("keepAtLeast", definition.keepAtLeast)
            }
        }
        return tag
    }

    private fun readDefinition(tag: CompoundTag): TaskDefinition {
        val id = tag.getString("id")
        val common = setOf("id", "version", "dimension", "duration", "attempts", "backoff")
        val budget = TaskBudget(tag.int("duration"), tag.int("attempts"), tag.int("backoff"))
        val dimension = tag.getString("dimension")
        val version = tag.int("version")
        val definition = when (id) {
            AttackTaskDefinition.ID -> {
                require(tag.allKeys == common + TaskCombatCodec.definitionKeys) { "unknown/missing attack parameter" }
                TaskCombatCodec.readDefinition(tag, dimension, budget, version)
            }
            LumberjackTaskDefinition.ID -> {
                require(tag.allKeys == common + setOf("area", "wood", "container", "quantity", "tools")) { "unknown/missing lumberjack parameter" }
                LumberjackTaskCodec.readDefinition(tag, dimension, budget, version)
            }
            NavigateTaskDefinition.ID -> {
                require(tag.allKeys == common + setOf("destination", "speed", "arrivalDistance")) { "unknown/missing navigation parameter" }
                require(tag.contains("speed", Tag.TAG_FLOAT.toInt())) { "invalid speed type" }
                NavigateTaskDefinition(dimension, readPosition(compound(tag, "destination")), tag.getFloat("speed"), tag.double("arrivalDistance"), budget, version)
            }
            DeliveryTaskDefinition.ID -> {
                require(tag.allKeys == common + setOf("container", "item", "quantity", "keepAtLeast")) { "unknown/missing delivery parameter" }
                val position = compound(tag, "container")
                DeliveryTaskDefinition(dimension, io.samcnpc.core.api.NpcBlockPosition(position.int("x"), position.int("y"), position.int("z")),
                    tag.getString("item"), tag.int("quantity"), tag.int("keepAtLeast"), budget, version)
            }
            else -> errorValue("unknown operation $id")
        }
        val problem = definition.validationProblem()
        require(problem == null) { problem.orEmpty() }
        return definition
    }

    private fun position(position: NpcPosition) = CompoundTag().apply {
        putDouble("x", position.x); putDouble("y", position.y); putDouble("z", position.z)
    }
    private fun readPosition(tag: CompoundTag): NpcPosition {
        val position = NpcPosition(tag.double("x"), tag.double("y"), tag.double("z"))
        require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) { "non-finite task position" }
        return position
    }
    private fun reason(tag: CompoundTag) = TaskReason.entries.firstOrNull { it.name == tag.getString("reason") } ?: errorValue("unknown task reason")
    private fun compound(tag: CompoundTag, key: String): CompoundTag {
        require(tag.contains(key, Tag.TAG_COMPOUND.toInt())) { "missing compound $key" }
        return tag.getCompound(key)
    }
    private fun list(tag: CompoundTag, key: String, type: Byte, limit: Int): ListTag {
        val list = tag.get(key) as? ListTag ?: errorValue("missing list $key")
        require(list.size <= limit && (list.isEmpty() || list.elementType == type)) { "invalid/oversized list $key" }
        return list
    }
    private fun CompoundTag.int(key: String): Int {
        require(contains(key, Tag.TAG_INT.toInt())) { "missing integer $key" }
        return getInt(key)
    }
    private fun CompoundTag.double(key: String): Double {
        require(contains(key, Tag.TAG_DOUBLE.toInt())) { "missing double $key" }
        return getDouble(key)
    }
    private fun errorValue(message: String): Nothing = throw IllegalArgumentException(message)
}
