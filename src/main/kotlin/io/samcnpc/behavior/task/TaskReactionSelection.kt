package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.core.api.*
import java.util.UUID

/** Read-only snapshot phase. Only the selected action consumes a pending trigger. */
internal object TaskReactionSelection {
    data class Candidate(val attacker: UUID?, val anchor: NpcPosition, val hit: TaskProtectionHit? = null)

    fun candidate(record: TaskRecord?, snapshot: NpcSnapshot, world: NpcWorldView?, scheduledAreaSearch: Boolean = false): Candidate? {
        if (record == null || !canInterrupt(record) || record.reaction.cooldownRemaining != 0 ||
            snapshot.dimensionId != record.primary.definition.dimensionId || world != null && world.dimensionId != snapshot.dimensionId) return null
        val state = record.reaction
        val policy = state.policy
        val anchor = policy.anchor ?: snapshot.position
        val area = CombatTargetSelector.Area(anchor, policy.leash)
        if (!area.contains(snapshot.position)) return null
        return when (policy.mode) {
            TaskReactionMode.PASSIVE -> null
            TaskReactionMode.RETALIATE -> if (snapshot.lastDamageEventId != null && snapshot.lastDamageAgeTicks in 0L..100L &&
                snapshot.lastDamageEventId != state.handledDamage) Candidate(snapshot.lastDamageSourceEntityUuid, anchor) else null
            TaskReactionMode.PROTECT_SUMMONER, TaskReactionMode.PROTECT_UNIT -> {
                if (world == null || subjectProblem(policy, snapshot, world) != null) return null
                val hit = protectionHit(policy, snapshot, world) ?: return null
                if (hit == state.consumedProtectionHit) null else Candidate(hit.attacker, anchor, hit)
            }
            TaskReactionMode.AREA -> {
                // Direct callers retain the fixed cadence; the runtime admits a queued search before entering this read-only selection.
                if (world == null || !scheduledAreaSearch && Math.floorMod(snapshot.gameTime, 10L).toInt() != Math.floorMod(snapshot.npcUuid.hashCode(), 10)) return null
                val target = CombatTargetSelector.filtered(snapshot, world, area, policy.filter, allowPlayers = policy.allowPlayers)
                target?.let { Candidate(it.uuid, anchor) }
            }
        }
    }

    fun canInterrupt(record: TaskRecord): Boolean = !record.status.terminal && record.status != TaskStatus.PAUSED &&
        record.frames.none { it.definition is AttackTaskDefinition || it.definition is CombatMissionDefinition } &&
        record.frames.size < TaskRecord.MAX_FRAMES && record.completedInterruptions + record.frames.size - 1 < 32

    fun subjectProblem(policy: TaskReactionPolicy, snapshot: NpcSnapshot, world: NpcWorldView): String? {
        val subject = policy.subjectUuid ?: return null
        if (subject == snapshot.npcUuid) return "NPC cannot protect itself; use retaliate"
        if (policy.mode == TaskReactionMode.PROTECT_SUMMONER && subject != snapshot.summonerUuid) return "protected summoner binding changed"
        val observed = world.observeEntity(subject) ?: return "protected subject is not loaded/observable"
        if (!observed.alive || observed.combat == null) return "protected subject is not alive"
        if (!CombatTargetSelector.Area(checkNotNull(policy.anchor), policy.leash).contains(observed.position)) return "protected subject left the fixed reaction boundary"
        return null
    }

    fun protectionHit(policy: TaskReactionPolicy, snapshot: NpcSnapshot, world: NpcWorldView): TaskProtectionHit? {
        val subject = policy.subjectUuid ?: return null
        val facts = world.observeEntity(subject)?.combat ?: return null
        val attacker = facts.lastAttackerUuid ?: return null
        val age = facts.lastAttackAgeTicks ?: return null
        if (age !in 0L..100L || snapshot.gameTime < age) return null
        return TaskProtectionHit(subject, attacker, snapshot.gameTime - age)
    }

    fun consume(record: TaskRecord, snapshot: NpcSnapshot, candidate: Candidate) {
        record.reaction.handledDamage = snapshot.lastDamageEventId
        if (candidate.hit != null) record.reaction.consumedProtectionHit = candidate.hit
    }
}
