package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.core.api.*

/** Finite exact-target melee; only called after its complete intent wins the common arbitration. */
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
        val target = world.observeEntity(definition.targetUuid)
        if (target == null) return end(record, TaskReason.TARGET_UNAVAILABLE, "supplied UUID is unavailable; no replacement or kill is authorized")
        if (!target.alive) return end(record, TaskReason.TARGET_ENDED, "supplied target died before this selected attack; no kill credited")
        if (!area.contains(snapshot.position) || !area.contains(target.position)) return end(record, TaskReason.LEASH_REACHED, "fixed chase boundary reached")
        if (!CombatTargetSelector.eligible(snapshot, target, area, definition.allowPlayers, requireVisible = false)) {
            return end(record, TaskReason.PERMISSION_CHANGED, "supplied target no longer passes relationship/player/world permissions")
        }
        val visible = target.combat?.visible == true
        val distance = TaskNavigator.distanceSquared(snapshot.position, target.position)
        val look = npc.lookAtEntity(target.uuid)
        if (look.status in FAILURE) return end(record, TaskReason.TARGET_UNAVAILABLE, look.detail)
        if (visible && distance <= 2.4 * 2.4) {
            if (snapshot.navigation != null || snapshot.control != null) npc.stopControl()
            execution.navigationId = null
            execution.completion = null
            execution.combatRoute = null
            execution.lastProgressTick = snapshot.gameTime
            if (snapshot.attackStrength < 0.9F) return NpcActionResult.running("combat waiting for the actual melee cooldown")
            val result = npc.attackEntity(target.uuid)
            if (result.status == NpcActionStatus.SUCCEEDED) {
                val after = world.observeEntity(target.uuid)
                if (after != null && !after.alive) {
                    record.endCombat(TaskStatus.COMPLETED, TaskReason.TARGET_DEFEATED, 1, "supplied target defeated by the selected physical melee hit")
                }
            } else if (result.code == NpcActionCode.PERMISSION_DENIED) return end(record, TaskReason.PERMISSION_CHANGED, result.detail)
            return result
        }
        return approach(record, execution, npc, snapshot, target)
    }

    private fun approach(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, snapshot: NpcSnapshot, target: NpcEntityObservation): NpcActionResult {
        val completion = execution.completion
        execution.completion = null
        if (completion != null) return retry(record, "combat route ended before visible melee reach: ${completion.code}")
        val now = snapshot.gameTime
        val old = execution.combatRoute
        val previousRepath = execution.lastRepathTick
        if (old == null || previousRepath == null || (now - previousRepath >= 20 && TaskNavigator.distanceSquared(old.position, target.position) >= 0.25)) {
            execution.combatRoute = NpcNavigationRequest(target.position, 1.0F, 1.5)
            execution.lastRepathTick = now
            execution.bestDistanceSquared = TaskNavigator.distanceSquared(snapshot.position, target.position)
        }
        val route = checkNotNull(execution.combatRoute)
        val distance = TaskNavigator.distanceSquared(snapshot.position, route.position)
        if (execution.lastProgressTick == null || distance < execution.bestDistanceSquared - 0.0625) {
            execution.bestDistanceSquared = distance
            execution.lastProgressTick = now
        }
        val lastProgress = checkNotNull(execution.lastProgressTick)
        if (now < lastProgress || now - lastProgress >= 120) return retry(record, "combat approach made no physical progress for 120 ticks")
        // Updating a moving endpoint can rebase distance, but never renews a stuck body's deadline.
        val result = npc.navigateTo(route)
        if (result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING) execution.navigationId = result.actionId
        else return retry(record, "combat navigation cannot reach visible melee range: ${result.code}")
        return result
    }

    private fun retry(record: TaskRecord, detail: String): NpcActionResult {
        record.retry(TaskReason.NO_PROGRESS, detail)
        return NpcActionResult.running(detail)
    }
    private fun end(record: TaskRecord, reason: TaskReason, detail: String): NpcActionResult {
        record.endCombat(TaskStatus.CANCELLED, reason, 0, detail)
        return NpcActionResult.succeeded(detail)
    }
    private val FAILURE = setOf(NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED)
}
