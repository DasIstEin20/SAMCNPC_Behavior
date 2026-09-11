package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Explicit trigger/selection phase; conditions call requested(), never begin(). */
internal object TaskCombatReactions {
    fun requested(record: TaskRecord?, snapshot: NpcSnapshot): Boolean {
        if (record == null || record.status.terminal || record.status == TaskStatus.PAUSED) return false
        val reaction = record.reaction
        return reaction.policy.mode == TaskReactionMode.RETALIATE && reaction.cooldownRemaining == 0 &&
            record.frames.none { it.definition is AttackTaskDefinition } && snapshot.lastDamageEventId != null &&
            snapshot.lastDamageAgeTicks in 0L..100L && snapshot.lastDamageEventId != reaction.handledDamage
    }

    fun observe(record: TaskRecord, snapshot: NpcSnapshot) {
        if (record.status == TaskStatus.PAUSED || record.reaction.policy.mode == TaskReactionMode.PASSIVE ||
            record.frames.any { it.definition is AttackTaskDefinition }) record.reaction.handledDamage = snapshot.lastDamageEventId
    }

    fun begin(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val store = TaskStore.forServer(server)
        val record = store.get(npc.npcUuid) ?: return NpcActionResult.rejected("no task to interrupt", NpcActionCode.NOT_READY)
        val snapshot = npc.snapshot()
        if (!requested(record, snapshot)) return NpcActionResult.running("no new eligible task reaction")
        record.reaction.handledDamage = snapshot.lastDamageEventId
        val policy = record.reaction.policy
        val attacker = snapshot.lastDamageSourceEntityUuid
        val area = CombatTargetSelector.Area(snapshot.position, policy.leash)
        val target = attacker?.let { CombatTargetSelector.exact(snapshot, world, it, area, policy.allowPlayers) }
        if (target == null) {
            record.detail = "new damage consumed; source is unavailable, unseen or outside the permitted reaction policy"
            store.changed()
            return NpcActionResult.succeeded(record.detail)
        }
        val definition = AttackTaskDefinition(snapshot.dimensionId, target.uuid, snapshot.position, policy.leash,
            policy.allowPlayers, TaskBudget(ticks = policy.durationTicks))
        val result = TaskService.interrupt(server, npc.npcUuid, definition)
        if (result.status != NpcActionStatus.SUCCEEDED) {
            record.detail = "new damage consumed; reaction rejected: ${result.detail}".take(TaskRecord.MAX_DETAIL_LENGTH)
            store.changed()
        }
        return result
    }

    fun configure(server: MinecraftServer, npcUuid: UUID, policy: TaskReactionPolicy): NpcActionResult {
        val problem = policy.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem)
        val store = TaskStore.forServer(server)
        val record = store.get(npcUuid) ?: return NpcActionResult.rejected("no durable task", NpcActionCode.NOT_READY)
        if (record.status.terminal) return NpcActionResult.rejected("task already has a final report", NpcActionCode.NOT_READY)
        val service = CoreNpcApi.service(server)
        val npc = service.find(npcUuid)?.let(service::runtime) ?: return NpcActionResult.rejected("task body is unavailable", NpcActionCode.NOT_FOUND)
        record.reaction.policy = policy
        record.reaction.handledDamage = npc.snapshot().lastDamageEventId
        if (policy.mode == TaskReactionMode.PASSIVE && record.frames.size > 1 && record.active.definition is AttackTaskDefinition) {
            // A paused reaction retains its paused primary after its attack intent is withdrawn.
            val paused = record.status == TaskStatus.PAUSED
            if (paused) record.resume()
            record.endCombat(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, 0, "task reaction changed to passive")
            if (paused) record.pause()
            BehaviorRuntimeService.releaseTaskControl(server, npcUuid)
        }
        store.changed()
        return NpcActionResult.succeeded("task reaction ${policy.mode}; leash=${policy.leash} duration=${policy.durationTicks} cooldown=${policy.cooldownTicks} allowPlayers=${policy.allowPlayers}")
    }
}
