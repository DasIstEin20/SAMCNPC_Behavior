package io.samcnpc.behavior.operations

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.combat.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.server.level.ServerPlayer

/** Shared fixture adapter: preserve each existing case's exact definition and physical oracles. */
internal object OperationPublicAssignmentProof {
    fun assign(scene: OperationScene, actor: ServerPlayer, definition: TaskDefinition): Boolean {
        val order = order(definition) ?: return false
        actor.teleportTo(scene.level, scene.body.x, scene.body.y + 4, scene.body.z + 5, 0F, 0F)
        val observed = OperationSupervisionApi.observe(scene.server, actor, scene.npcId)
        check(observed.result.status == NpcActionStatus.SUCCEEDED) { observed.result.detail }
        val view = checkNotNull(observed.observation)
        val request = OperationAssignmentRequest(view.task?.taskId, view.observedTick, view.observedTick + 1200, order)
        val reply = OperationSupervisionApi.assign(scene.server, actor, scene.npcId, request)
        check(reply.result.status == NpcActionStatus.SUCCEEDED) { reply.result.detail }
        check(TaskCodec.writeDefinition(scene.record.primary.definition) == TaskCodec.writeDefinition(definition))
        scene.initialPublicAssignment = request
        rejectReplay(scene, actor)
        val before = TaskCodec.write(scene.record)
        val conflict = OperationSupervisionApi.assign(scene.server, actor, scene.npcId,
            request.copy(expectedPriorTaskId = scene.record.id))
        check(conflict.result.code == NpcActionCode.CONFLICT && TaskCodec.write(scene.record) == before)
        return true
    }

    fun rejectReplay(scene: OperationScene, actor: ServerPlayer) {
        val request = scene.initialPublicAssignment ?: return
        actor.teleportTo(scene.level, scene.body.x, scene.body.y + 4, scene.body.z + 5, 0F, 0F)
        val task = TaskCodec.write(scene.record)
        val inventory = scene.inventoryTag()
        val reply = OperationSupervisionApi.assign(scene.server, actor, scene.npcId, request)
        check(reply.result.code == NpcActionCode.CONFLICT && reply.observation?.task?.taskId == scene.record.id)
        check(TaskCodec.write(scene.record) == task && scene.inventoryTag() == inventory)
    }

    fun order(definition: TaskDefinition): OperationOrder? {
        val budget = OperationBudget(definition.budget.ticks, definition.budget.attempts, definition.budget.backoffTicks)
        return when (definition) {
            is NavigateTaskDefinition -> OperationOrder.Navigate(definition.dimensionId, definition.destination,
                definition.speed, definition.arrivalDistance, budget)
            is DeliveryTaskDefinition -> if (definition.version != 2) null else OperationOrder.Deliver(definition.dimensionId,
                definition.destination, definition.itemId, definition.quantity, checkNotNull(definition.anchor), definition.keepAtLeast, budget)
            is TransportTaskDefinition -> OperationOrder.Transport(definition.dimensionId, containers(definition.sources),
                containers(definition.destinations), definition.itemId, definition.quantity, definition.anchor,
                definition.travelRadius, definition.keepAtLeast, definition.sourceKeepAtLeast, definition.returnTo, budget)
            is MachineTaskDefinition -> OperationOrder.Machine(definition.dimensionId,
                OperationMachineFeeds(definition.feeds.ports.map(::port)), port(definition.output), definition.anchor,
                definition.travelRadius, definition.returnTo, definition.pollTicks, definition.noProgressTicks, budget)
            is FishingTaskDefinition -> OperationOrder.Fish(definition.dimensionId, definition.water, definition.standing,
                definition.catches, definition.anchor, definition.travelRadius, definition.returnTo, definition.pickupWaitTicks, budget)
            is ExplorerTaskDefinition -> OperationOrder.Explore(definition.dimensionId, definition.anchor, definition.radius,
                definition.cellStep, definition.maxCells, definition.verticalRange, definition.chunkBudget, definition.heading, budget)
            is AttackTaskDefinition -> if (definition.version != 2) null else OperationCombatOrder.Attack(definition.dimensionId,
                definition.targetUuid, definition.anchor, definition.leash, definition.allowPlayers, budget, tactics(definition.tactics))
            is DefendTaskDefinition -> OperationCombatOrder.Defend(definition.dimensionId, definition.anchor, definition.leash,
                definition.subjectUuid, definition.filter, definition.dutyTicks, definition.returnTo, definition.allowPlayers,
                tactics(definition.tactics), budget)
            is AreaAttackTaskDefinition -> OperationCombatOrder.AreaAttack(definition.dimensionId, definition.anchor, definition.leash,
                definition.filter, definition.quota, definition.returnTo, definition.allowPlayers, tactics(definition.tactics), budget)
            is PatrolTaskDefinition -> OperationCombatOrder.Patrol(definition.dimensionId, definition.anchor, definition.leash,
                definition.route, definition.rounds, definition.dwellTicks, when (definition.reaction) {
                    PatrolReaction.PASSIVE -> OperationPatrolReaction.PASSIVE
                    PatrolReaction.RETALIATE -> OperationPatrolReaction.RETALIATE
                    PatrolReaction.PROTECT_SUMMONER -> OperationPatrolReaction.PROTECT_SUMMONER
                    PatrolReaction.SUPPORT -> OperationPatrolReaction.SUPPORT
                    PatrolReaction.AREA -> OperationPatrolReaction.AREA
                }, definition.subjectUuid, definition.supportTargetUuid, definition.filter, definition.returnTo,
                definition.allowPlayers, tactics(definition.tactics), budget)
            is InventoryTaskDefinition -> OperationInventoryOrder(definition.dimensionId, when (val value = definition.work) {
                is SupplyStock -> OperationInventoryWork.Supply(value.needs.map {
                    OperationStockNeed(it.itemId, it.minimum, it.target, it.sourceReserve)
                }, containers(value.containers))
                is UnloadExcess -> OperationInventoryWork.Unload(value.reserves.map {
                    OperationItemReserve(it.itemId, it.keep)
                }, containers(value.containers))
                is PickupNearby -> OperationInventoryWork.Pickup(value.itemIds, value.radius, value.maxItems)
            }, definition.anchor, definition.returnTo, definition.travelRadius, definition.workTicks, definition.maxSteps, budget)
            else -> OperationPublicHarvestProof.order(definition)
        }
    }

    private fun tactics(value: CombatTactics) = OperationCombatTactics(when (value.preference) {
        CombatWeaponPreference.CURRENT -> OperationWeaponPreference.CURRENT
        CombatWeaponPreference.MELEE -> OperationWeaponPreference.MELEE
        CombatWeaponPreference.RANGED -> OperationWeaponPreference.RANGED
        CombatWeaponPreference.AUTO -> OperationWeaponPreference.AUTO
    }, when (value.allowed) {
        CombatWeaponAllowance.MELEE -> OperationWeaponAllowance.MELEE
        CombatWeaponAllowance.RANGED -> OperationWeaponAllowance.RANGED
        CombatWeaponAllowance.BOTH -> OperationWeaponAllowance.BOTH
    }, value.equipArmor, value.useShield, value.heal, value.retreatAt, value.returnAt, value.rangedMinDistance, value.rangedMaxDistance)

    private fun containers(value: ContainerChoices) = OperationContainers(value.positions, when (value.preference) {
        ContainerPreference.ORDERED -> OperationContainerPreference.ORDERED
        ContainerPreference.NEAREST -> OperationContainerPreference.NEAREST
    })
    private fun port(value: MachinePort) = OperationMachinePort(value.endpoint, value.slot, value.itemId, value.quantity)
}
