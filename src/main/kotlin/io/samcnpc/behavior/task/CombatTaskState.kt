package io.samcnpc.behavior.task

import java.util.UUID

/** Durable tactical progress, never a path, active hand action, entity reference or cache. */
internal class CombatTaskState(
    var retreating: Boolean = false,
    var recoveryTicks: Int = 400,
    var healingCooldown: Int = 0,
    var healingBaseline: Double? = null,
    var healingUses: Int = 0,
    var observedHealing: Boolean = false,
    var selectedTarget: UUID? = null,
    var attackSubmitted: Boolean = false,
    val defeatedTargets: MutableSet<UUID> = linkedSetOf(),
    var patrolIndex: Int = 0,
    var patrolRounds: Int = 0,
    var dwellTicks: Int = 0,
    var returning: Boolean = false,
    var dutyTicks: Int = 0,
    var waypointReached: Boolean = false,
    var consumedThreatTick: Long? = null,
    var consumedThreatAttacker: UUID? = null,
    var supportFinished: Boolean = false,
) {
    fun advance(ticks: Int, dwell: Boolean = false) {
        require(ticks >= 0)
        healingCooldown = (healingCooldown - ticks).coerceAtLeast(0)
        if (retreating) recoveryTicks = (recoveryTicks - ticks).coerceAtLeast(0)
        if (dwell) dwellTicks = (dwellTicks - ticks).coerceAtLeast(0)
        dutyTicks = (dutyTicks - ticks).coerceAtLeast(0)
    }
    companion object {
        fun forDefinition(definition: TaskDefinition): CombatTaskState? = when (definition) {
            is AttackTaskDefinition -> CombatTaskState(selectedTarget = definition.targetUuid)
            is DefendTaskDefinition -> CombatTaskState(dutyTicks = definition.dutyTicks)
            is CombatMissionDefinition -> CombatTaskState()
            else -> null
        }
    }
}
