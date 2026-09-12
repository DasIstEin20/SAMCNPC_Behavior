package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Explicit trigger/selection phase; conditions consume only the captured readiness flag. */
internal object TaskCombatReactions {
    fun requested(record: TaskRecord?, snapshot: NpcSnapshot, world: NpcWorldView? = null): Boolean =
        TaskReactionSelection.candidate(record, snapshot, world) != null

    fun observe(record: TaskRecord, snapshot: NpcSnapshot, world: NpcWorldView? = null) {
        if (!TaskReactionSelection.canInterrupt(record) || record.reaction.policy.mode == TaskReactionMode.PASSIVE) {
            record.reaction.handledDamage = snapshot.lastDamageEventId
            if (world != null && record.reaction.policy.protectsSubject) {
                val hit = TaskReactionSelection.protectionHit(record.reaction.policy, snapshot, world)
                if (hit != null) record.reaction.consumedProtectionHit = hit
            }
        }
    }

    fun begin(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val store = TaskStore.forServer(server)
        val record = store.get(npc.npcUuid) ?: return NpcActionResult.rejected("no task to interrupt", NpcActionCode.NOT_READY)
        val snapshot = npc.snapshot()
        val candidate = TaskReactionReadiness.candidate(record, snapshot, world)
            ?: return NpcActionResult.running("no new eligible task reaction")
        TaskReactionSelection.consume(record, snapshot, candidate)
        val policy = record.reaction.policy
        val area = CombatTargetSelector.Area(candidate.anchor, policy.leash)
        val target = candidate.attacker?.let { CombatTargetSelector.exact(snapshot, world, it, area, policy.allowPlayers) }
        if (target == null) {
            record.detail = "reaction trigger consumed; source is unavailable, unseen or outside the permitted policy"
            store.changed()
            return NpcActionResult.succeeded(record.detail)
        }
        val definition = AttackTaskDefinition(snapshot.dimensionId, target.uuid, candidate.anchor, policy.leash,
            policy.allowPlayers, TaskBudget(ticks = policy.durationTicks), tactics = policy.tactics)
        val result = TaskService.interrupt(server, npc.npcUuid, definition)
        if (result.status == NpcActionStatus.SUCCEEDED) record.reaction.activeFrame = record.active.id
        else record.detail = "reaction trigger consumed; interruption rejected: ${result.detail}".take(TaskRecord.MAX_DETAIL_LENGTH)
        store.changed()
        return result
    }

    fun configure(server: MinecraftServer, npcUuid: UUID, policy: TaskReactionPolicy): NpcActionResult {
        val problem = policy.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem)
        val store = TaskStore.forServer(server)
        val record = store.get(npcUuid) ?: return NpcActionResult.rejected("no durable task", NpcActionCode.NOT_READY)
        if (record.status.terminal) return NpcActionResult.rejected("task already has a final report", NpcActionCode.NOT_READY)
        if (record.primary.definition is AttackTaskDefinition || record.primary.definition is CombatMissionDefinition) {
            return NpcActionResult.rejected("configure reactions on ordinary work; combat missions have their own target policy", NpcActionCode.CONFLICT)
        }
        val service = CoreNpcApi.service(server)
        val npc = service.find(npcUuid)?.let(service::runtime) ?: return NpcActionResult.rejected("task body is unavailable", NpcActionCode.NOT_FOUND)
        val reviseProblem = revise(record, npc, npc.worldView(), policy)
        if (reviseProblem != null) return NpcActionResult.rejected(reviseProblem)
        BehaviorRuntimeService.releaseTaskControl(server, npcUuid)
        store.changed()
        return NpcActionResult.succeeded("task reaction ${policy.mode}; original intent and budget retained")
    }

    internal fun revise(record: TaskRecord, npc: NpcFacade, world: NpcWorldView, policy: TaskReactionPolicy): String? {
        val problem = policy.validationProblem()
        if (problem != null) return problem
        if (record.primary.definition is AttackTaskDefinition || record.primary.definition is CombatMissionDefinition) return "configure reactions on ordinary work; missions have their own policy"
        val snapshot = npc.snapshot()
        if (snapshot.dimensionId != record.primary.definition.dimensionId) return "task dimension changed"
        if (policy.anchor != null && !CombatTargetSelector.Area(policy.anchor, policy.leash).contains(snapshot.position)) return "NPC must be inside the fixed reaction boundary"
        val subjectProblem = TaskReactionSelection.subjectProblem(policy, snapshot, world)
        if (subjectProblem != null) return subjectProblem
        val reactionFrame = record.reaction.activeFrame
        if (reactionFrame != null && reactionFrame != record.active.id) {
            return "finish the navigation interruption above the reaction before changing its policy"
        }
        if (reactionFrame != null) {
            // Withdrawing a reaction must preserve the paused primary and its original budget.
            val paused = record.status == TaskStatus.PAUSED
            if (paused) record.resume()
            record.endCombat(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, 0, "task reaction policy changed")
            if (paused) record.pause()
        }
        record.reaction.policy = policy
        record.reaction.handledDamage = snapshot.lastDamageEventId
        record.reaction.consumedProtectionHit = TaskReactionSelection.protectionHit(policy, snapshot, world)
        return null
    }
}
