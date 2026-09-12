package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.core.api.*
import java.util.UUID

/** Target retention is independent of new damage: repeated hits cannot restart a charge or route. */
internal object CombatMissionTargets {
    fun subject(definition: CombatMissionDefinition): UUID? = when (definition) {
        is DefendTaskDefinition -> definition.subjectUuid
        is PatrolTaskDefinition -> definition.subjectUuid
        is AreaAttackTaskDefinition -> null
    }
    fun subjectProblem(definition: CombatMissionDefinition, npc: NpcSnapshot, world: NpcWorldView): String? {
        val uuid = subject(definition) ?: return null
        val observation = world.observeEntity(uuid) ?: return "protected subject is not loaded/observable"
        if (!observation.alive || observation.combat == null) return "protected subject is not alive"
        if (!definition.area().contains(observation.position)) return "protected subject left the fixed mission boundary"
        if (uuid == npc.npcUuid) return "NPC cannot be its own protected subject"
        if (definition is PatrolTaskDefinition && definition.reaction == PatrolReaction.PROTECT_SUMMONER && uuid != npc.summonerUuid) return "protected summoner binding changed"
        return null
    }
    fun choose(definition: CombatMissionDefinition, state: CombatTaskState, snapshot: NpcSnapshot, world: NpcWorldView, onDeferred: () -> Unit = {}): NpcEntityObservation? {
        val retained = state.selectedTarget
        if (retained != null) {
            val target = world.observeEntity(retained, definition.filter)
            if (target != null && CombatTargetSelector.eligible(snapshot, target, definition.area(), definition.allowPlayers, requireVisible = false)) return target
        }
        val areaSearch = definition is AreaAttackTaskDefinition || definition is DefendTaskDefinition && definition.subjectUuid == null ||
            definition is PatrolTaskDefinition && definition.reaction == PatrolReaction.AREA
        if (areaSearch && !io.samcnpc.behavior.runtime.BehaviorPlanning.admit(world, 65, io.samcnpc.behavior.kernel.work.PlanningKind.COMBAT_SEARCH)) {
            onDeferred()
            return null
        }
        return when (definition) {
            is AreaAttackTaskDefinition -> CombatTargetSelector.filtered(snapshot, world, definition.area(), definition.filter,
                allowPlayers = definition.allowPlayers, excluded = state.defeatedTargets)
            is DefendTaskDefinition -> if (definition.subjectUuid == null) {
                CombatTargetSelector.filtered(snapshot, world, definition.area(), definition.filter, allowPlayers = definition.allowPlayers)
            } else protectedThreat(definition.subjectUuid, definition, state, snapshot, world)
            is PatrolTaskDefinition -> when (definition.reaction) {
                PatrolReaction.PASSIVE -> null
                PatrolReaction.RETALIATE -> threat(snapshot.lastDamageSourceEntityUuid, snapshot.lastDamageAgeTicks, definition, state, snapshot, world)
                PatrolReaction.PROTECT_SUMMONER -> protectedThreat(checkNotNull(definition.subjectUuid), definition, state, snapshot, world)
                PatrolReaction.SUPPORT -> if (state.supportFinished) null else CombatTargetSelector.exact(snapshot, world,
                    checkNotNull(definition.supportTargetUuid), definition.area(), definition.allowPlayers)
                PatrolReaction.AREA -> CombatTargetSelector.filtered(snapshot, world, definition.area(), definition.filter, allowPlayers = definition.allowPlayers)
            }
        }
    }
    private fun protectedThreat(subject: UUID, definition: CombatMissionDefinition, state: CombatTaskState, snapshot: NpcSnapshot, world: NpcWorldView): NpcEntityObservation? {
        val facts = world.observeEntity(subject)?.combat ?: return null
        return threat(facts.lastAttackerUuid, facts.lastAttackAgeTicks, definition, state, snapshot, world)
    }
    private fun threat(uuid: UUID?, age: Long?, definition: CombatMissionDefinition, state: CombatTaskState, snapshot: NpcSnapshot, world: NpcWorldView): NpcEntityObservation? {
        if (uuid == null || age == null || age !in 0L..100L || snapshot.gameTime < age) return null
        val tick = snapshot.gameTime - age
        if (state.consumedThreatTick == tick && state.consumedThreatAttacker == uuid) return null
        state.consumedThreatTick = tick; state.consumedThreatAttacker = uuid
        val target = world.observeEntity(uuid, definition.filter) ?: return null
        return target.takeIf { CombatTargetSelector.eligible(snapshot, it, definition.area(), definition.allowPlayers) }
    }
}
