package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*

/** Conversion only; task amendment validation and safe-boundary rules remain authoritative. */
internal object TaskPublicPolicies {
    fun reaction(value: OperationReactionPolicy) = TaskReactionPolicy(when (value.mode) {
        OperationReactionMode.PASSIVE -> TaskReactionMode.PASSIVE
        OperationReactionMode.RETALIATE -> TaskReactionMode.RETALIATE
        OperationReactionMode.PROTECT_SUMMONER -> TaskReactionMode.PROTECT_SUMMONER
        OperationReactionMode.PROTECT_UNIT -> TaskReactionMode.PROTECT_UNIT
        OperationReactionMode.AREA -> TaskReactionMode.AREA
    }, value.leash, value.durationTicks, value.cooldownTicks, value.allowPlayers,
        TaskPublicCombatOrders.tactics(value.tactics), value.anchor, value.subjectUuid, value.filter)

    fun logistics(value: OperationLogisticsPolicy) = TaskLogisticsPolicy(value.anchor,
        value.supply?.let(TaskPublicInventoryOrders::supply), value.unload?.let(TaskPublicInventoryOrders::unload),
        value.pickup?.let(TaskPublicInventoryOrders::pickup), value.travelRadius, value.workTicks,
        value.durationTicks, value.cooldownTicks, value.maxSteps, value.preparation?.let(TaskPublicInventoryOrders::ensure))
}
