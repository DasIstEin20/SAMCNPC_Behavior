package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatEngagement
import io.samcnpc.behavior.combat.CombatStep
import io.samcnpc.core.api.*

/** One common action scope executes a mission; all locomotion/combat remains through Core primitives. */
internal object TaskCombatMission {
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val frame = record.active
        val definition = frame.definition as CombatMissionDefinition
        val state = checkNotNull(frame.combat)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) return cancel(record, TaskReason.DIMENSION_CHANGED, "mission dimension changed", failed = true)
        if (!definition.area().contains(snapshot.position)) return cancel(record, TaskReason.LEASH_REACHED, "NPC left the fixed mission boundary")
        if (!state.returning) {
            val problem = CombatMissionTargets.subjectProblem(definition, snapshot, world)
            if (problem != null) return cancel(record, TaskReason.SUBJECT_UNAVAILABLE, problem)
        }
        reconcileTarget(record, definition, state, npc, world, execution)
        if (definition is DefendTaskDefinition && state.dutyTicks == 0 || definition is AreaAttackTaskDefinition && state.defeatedTargets.size >= definition.quota) {
            if (!state.returning) {
                state.returning = true
                clearTarget(state, npc, execution)
            }
        }
        if (state.returning) return returnHome(record, definition, state, npc, execution)
        if (definition is PatrolTaskDefinition && definition.reaction == PatrolReaction.SUPPORT && !state.supportFinished) {
            val explicit = world.observeEntity(checkNotNull(definition.supportTargetUuid))
            if (explicit == null || !explicit.alive || !io.samcnpc.behavior.combat.CombatTargetSelector.eligible(snapshot, explicit, definition.area(), definition.allowPlayers, requireVisible = false)) {
                state.supportFinished = true
                clearTarget(state, npc, execution)
            }
        }
        val previous = state.selectedTarget
        var deferred = false
        val target = CombatMissionTargets.choose(definition, state, snapshot, world) { deferred = true }
        if (deferred) {
            // A retained target is checked before admission. Reaching this branch with an old
            // UUID means it became ineligible; its charge/navigation must not continue.
            if (previous != null) clearTarget(state, npc, execution)
            return NpcActionResult.running("target search deferred by shared planning budget; mission deadline retained")
        }
        if (target?.uuid != previous) {
            clearTarget(state, npc, execution)
            state.selectedTarget = target?.uuid
        }
        if (target != null) {
            val step = CombatEngagement.tick(npc, world, target, definition.area(), definition.tactics, state, execution)
            if (step.end != null) return cancel(record, step.end, step.action.detail)
            if (step.retry) record.retry(TaskReason.NO_PROGRESS, step.action.detail)
            return step.action
        }
        return when (definition) {
            is PatrolTaskDefinition -> patrol(record, definition, state, npc, execution)
            is DefendTaskDefinition -> {
                val subject = definition.subjectUuid?.let(world::observeEntity)
                move(record, npc, execution, subject?.position ?: definition.anchor, if (subject != null) 2.5 else 1.0).action
            }
            is AreaAttackTaskDefinition -> {
                val step = move(record, npc, execution, definition.anchor)
                if (!step.retry) record.detail = "waiting for visible permitted targets; confirmed defeats=${state.defeatedTargets.size}/${definition.quota}"
                step.action
            }
        }
    }

    private fun reconcileTarget(record: TaskRecord, definition: CombatMissionDefinition, state: CombatTaskState, npc: NpcFacade,
                                world: NpcWorldView, execution: TaskExecution) {
        val uuid = state.selectedTarget ?: return
        val observed = world.observeEntity(uuid)
        if (observed != null && observed.alive) return
        val defeated = observed != null && !observed.alive && state.attackSubmitted && observed.combat?.lastDamageSourceEntityUuid == npc.npcUuid
        if (definition is PatrolTaskDefinition && definition.reaction == PatrolReaction.SUPPORT) state.supportFinished = true
        if (defeated && definition is AreaAttackTaskDefinition) state.defeatedTargets.add(uuid)
        record.lastCombat = TaskCombatOutcome(uuid, if (defeated) TaskStatus.COMPLETED else TaskStatus.CANCELLED,
            if (defeated) TaskReason.TARGET_DEFEATED else TaskReason.TARGET_UNAVAILABLE, if (defeated) 1 else 0,
            if (defeated) "observed defeat attributed to this NPC" else "target unavailable; no defeat credited")
        clearTarget(state, npc, execution)
    }

    private fun patrol(record: TaskRecord, definition: PatrolTaskDefinition, state: CombatTaskState, npc: NpcFacade, execution: TaskExecution): NpcActionResult {
        val waypoint = definition.route[state.patrolIndex]
        val step = move(record, npc, execution, waypoint)
        if (step.action.status != NpcActionStatus.SUCCEEDED) {
            state.waypointReached = false; state.dwellTicks = 0
            return step.action
        }
        if (!state.waypointReached) {
            state.waypointReached = true; state.dwellTicks = definition.dwellTicks
        }
        if (state.dwellTicks > 0) return NpcActionResult.running("patrol dwell ${state.dwellTicks} ticks at waypoint ${state.patrolIndex + 1}")
        state.waypointReached = false
        state.patrolIndex++
        if (state.patrolIndex == definition.route.size) {
            state.patrolIndex = 0; state.patrolRounds++
            if (state.patrolRounds == definition.rounds) state.returning = true
        }
        CombatEngagement.release(npc, execution)
        record.detail = "patrol rounds=${state.patrolRounds}/${definition.rounds}; next waypoint=${state.patrolIndex + 1}"
        return NpcActionResult.running(record.detail)
    }

    private fun returnHome(record: TaskRecord, definition: CombatMissionDefinition, state: CombatTaskState, npc: NpcFacade, execution: TaskExecution): NpcActionResult {
        val step = move(record, npc, execution, definition.returnTo)
        if (step.action.status == NpcActionStatus.SUCCEEDED) {
            val reason = when (definition) {
                is DefendTaskDefinition -> TaskReason.DEFENSE_FINISHED
                is AreaAttackTaskDefinition -> TaskReason.AREA_CLEARED
                is PatrolTaskDefinition -> TaskReason.PATROL_FINISHED
            }
            record.completeActive(reason, "mission goal confirmed and return point physically reached; defeats=${state.defeatedTargets.size}; rounds=${state.patrolRounds}")
        }
        return step.action
    }
    private fun move(record: TaskRecord, npc: NpcFacade, execution: TaskExecution, position: NpcPosition, arrival: Double = 1.0): CombatStep {
        val result = CombatEngagement.move(npc, execution, position, arrival)
        if (result.retry) record.retry(TaskReason.NO_PROGRESS, result.action.detail)
        return result
    }
    private fun clearTarget(state: CombatTaskState, npc: NpcFacade, execution: TaskExecution) {
        CombatEngagement.release(npc, execution)
        state.selectedTarget = null; state.attackSubmitted = false
    }
    private fun cancel(record: TaskRecord, reason: TaskReason, detail: String, failed: Boolean = false): NpcActionResult {
        record.finish(if (failed) TaskStatus.FAILED else TaskStatus.CANCELLED, reason, detail)
        return NpcActionResult.succeeded(record.detail)
    }
}
