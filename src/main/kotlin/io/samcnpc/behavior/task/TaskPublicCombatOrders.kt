package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.combat.*

/** Conversion only: combat policy, permissions and effects remain in the existing executor. */
internal object TaskPublicCombatOrders {
    fun definition(order: OperationCombatOrder, budget: TaskBudget): TaskDefinition {
        val tactics = tactics(order.tactics)
        val version = order.type.definitionVersion
        return when (order) {
            is OperationCombatOrder.Attack -> AttackTaskDefinition(order.dimensionId, order.targetUuid,
                order.anchor, order.leash, order.allowPlayers, budget, version, tactics)
            is OperationCombatOrder.Defend -> DefendTaskDefinition(order.dimensionId, order.anchor,
                order.leash, order.subjectUuid, order.filter, order.dutyTicks, order.returnTo,
                order.allowPlayers, tactics, budget, version)
            is OperationCombatOrder.AreaAttack -> AreaAttackTaskDefinition(order.dimensionId, order.anchor,
                order.leash, order.filter, order.quota, order.returnTo, order.allowPlayers, tactics, budget, version)
            is OperationCombatOrder.Patrol -> PatrolTaskDefinition(order.dimensionId, order.anchor, order.leash,
                order.route, order.rounds, order.dwellTicks, when (order.reaction) {
                    OperationPatrolReaction.PASSIVE -> PatrolReaction.PASSIVE
                    OperationPatrolReaction.RETALIATE -> PatrolReaction.RETALIATE
                    OperationPatrolReaction.PROTECT_SUMMONER -> PatrolReaction.PROTECT_SUMMONER
                    OperationPatrolReaction.SUPPORT -> PatrolReaction.SUPPORT
                    OperationPatrolReaction.AREA -> PatrolReaction.AREA
                }, order.subjectUuid, order.supportTargetUuid, order.filter, order.returnTo,
                order.allowPlayers, tactics, budget, version)
        }
    }
    internal fun tactics(value: OperationCombatTactics) = CombatTactics(
        when (value.preference) {
            OperationWeaponPreference.CURRENT -> CombatWeaponPreference.CURRENT
            OperationWeaponPreference.MELEE -> CombatWeaponPreference.MELEE
            OperationWeaponPreference.RANGED -> CombatWeaponPreference.RANGED
            OperationWeaponPreference.AUTO -> CombatWeaponPreference.AUTO
        }, when (value.allowed) {
            OperationWeaponAllowance.MELEE -> CombatWeaponAllowance.MELEE
            OperationWeaponAllowance.RANGED -> CombatWeaponAllowance.RANGED
            OperationWeaponAllowance.BOTH -> CombatWeaponAllowance.BOTH
        }, value.equipArmor, value.useShield, value.heal, value.retreatAt, value.returnAt,
        value.rangedMinDistance, value.rangedMaxDistance)
}
