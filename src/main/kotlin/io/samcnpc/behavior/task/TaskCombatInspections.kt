package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.OperationValue
import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.behavior.task.TaskInspectionValues.v
import io.samcnpc.behavior.task.TaskInspectionValues.record
import io.samcnpc.behavior.task.TaskInspectionValues.sequence
import io.samcnpc.behavior.task.TaskInspectionValues.filter
import io.samcnpc.behavior.task.TaskInspectionValues.merged

internal object TaskCombatInspections {
    fun attack(d: AttackTaskDefinition) = record("targetUuid" to v(d.targetUuid), "anchor" to v(d.anchor),
        "leash" to v(d.leash), "allowPlayers" to v(d.allowPlayers), "tactics" to tactics(d.tactics))

    fun mission(d: CombatMissionDefinition): OperationValue.Record {
        val common = record("anchor" to v(d.anchor), "leash" to v(d.leash), "filter" to filter(d.filter),
            "returnTo" to v(d.returnTo), "allowPlayers" to v(d.allowPlayers), "tactics" to tactics(d.tactics))
        val fields = when (d) {
            is DefendTaskDefinition -> record("subjectUuid" to v(d.subjectUuid), "dutyTicks" to v(d.dutyTicks))
            is AreaAttackTaskDefinition -> record("quota" to v(d.quota))
            is PatrolTaskDefinition -> record("route" to sequence(d.route.map(::v)), "rounds" to v(d.rounds),
                "dwellTicks" to v(d.dwellTicks), "reaction" to v(d.reaction.name), "subjectUuid" to v(d.subjectUuid),
                "supportTargetUuid" to v(d.supportTargetUuid))
        }
        return merged(common, fields)
    }

    private fun tactics(t: CombatTactics) = record("preference" to v(t.preference.name), "allowed" to v(t.allowed.name),
        "equipArmor" to v(t.equipArmor), "useShield" to v(t.useShield), "heal" to v(t.heal),
        "retreatAt" to v(t.retreatAt), "returnAt" to v(t.returnAt), "rangedMinDistance" to v(t.rangedMinDistance),
        "rangedMaxDistance" to v(t.rangedMaxDistance))
}
