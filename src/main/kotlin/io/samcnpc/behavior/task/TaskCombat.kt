package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatEngagement
import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.core.api.*

/** Exact-target goal around the same engagement controller used by bounded combat operations. */
internal object TaskCombat {
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = record.active.definition as AttackTaskDefinition
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) {
            record.finish(TaskStatus.FAILED, TaskReason.DIMENSION_CHANGED, "combat is outside its assigned dimension")
            return NpcActionResult.failed(record.detail)
        }
        val area = CombatTargetSelector.Area(definition.anchor, definition.leash)
        val state = checkNotNull(record.active.combat)
        val target = world.observeEntity(definition.targetUuid)
            ?: return end(record, TaskReason.TARGET_UNAVAILABLE, "supplied UUID unavailable; no replacement or kill is authorized")
        if (!target.alive) {
            if (state.attackSubmitted && target.combat?.lastDamageSourceEntityUuid == snapshot.npcUuid) return defeated(record)
            return end(record, TaskReason.TARGET_ENDED, "supplied target died without a confirmed selected hit; no kill credited")
        }
        if (!area.contains(snapshot.position) || !area.contains(target.position)) return end(record, TaskReason.LEASH_REACHED, "fixed chase boundary reached")
        if (!CombatTargetSelector.eligible(snapshot, target, area, definition.allowPlayers, requireVisible = false)) {
            return end(record, TaskReason.PERMISSION_CHANGED, "supplied target no longer passes relationship/player/world permissions")
        }
        val step = CombatEngagement.tick(npc, world, target, area, definition.tactics, state, execution)
        if (step.end != null) return end(record, step.end, step.action.detail)
        if (step.retry) record.retry(TaskReason.NO_PROGRESS, step.action.detail)
        if (step.action.status == NpcActionStatus.SUCCEEDED && state.attackSubmitted) {
            val after = world.observeEntity(target.uuid)
            if (after != null && !after.alive && after.combat?.lastDamageSourceEntityUuid == snapshot.npcUuid) return defeated(record)
        }
        return step.action
    }
    private fun defeated(record: TaskRecord): NpcActionResult {
        record.endCombat(TaskStatus.COMPLETED, TaskReason.TARGET_DEFEATED, 1, "supplied target defeated by a confirmed NPC-attributed physical hit")
        return NpcActionResult.succeeded(record.detail)
    }
    private fun end(record: TaskRecord, reason: TaskReason, detail: String): NpcActionResult {
        record.endCombat(TaskStatus.CANCELLED, reason, 0, detail)
        return NpcActionResult.succeeded(detail)
    }
}