package io.samcnpc.behavior.task

import io.samcnpc.behavior.inventory.EquipmentPreparation
import io.samcnpc.behavior.inventory.InventoryFacts
import io.samcnpc.behavior.inventory.VisibleContainerFacts
import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Bounded observe/approach/transfer/equip steps inside the existing durable inventory frame. */
internal object InventoryPreparationWork {
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView,
             definition: InventoryTaskDefinition, state: InventoryWorkState, work: EnsureItems): NpcActionResult {
        val facts = InventoryFacts.capture(npc)
        val destination = work.destination
        val equipped = destination != null && facts.equippedMatches(work.query, destination, work.minimumDurability)
        val carriedEquipment = destination?.let { EquipmentPreparation.choose(facts.inventory, work.query, it, work.minimumDurability, npc.snapshot().selectedHotbarSlot) }
        if (equipped || destination == null && facts.count(work.query, work.minimumDurability) >= work.count || carriedEquipment != null) {
            if (destination != null && !equipped) {
                state.steps++
                val equipped = EquipmentPreparation.ensure(npc, work.query, destination, work.minimumDurability)
                if (equipped.status != NpcActionStatus.SUCCEEDED) return TaskInventory.beginReturn(state, execution, npc,
                    InventoryWorkReason.MISSING_EQUIPMENT, equipped.detail)
                return equipped
            }
            return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.SATISFIED, "fresh inventory/equipment satisfies ${work.query.encode()}")
        }
        val snapshot = npc.snapshot()
        val choices = work.containers
        if (choices == null) return TaskInventory.beginReturn(state, execution, npc, missing(work), "no usable carried match and no authorized supply source")
        if (state.selected == null) {
            val ordered = if (choices.preference == ContainerPreference.ORDERED) choices.positions else choices.positions.sortedWith(
                compareBy<NpcBlockPosition> { TaskNavigator.distanceSquared(snapshot.position, TransportTaskDefinition.center(it)) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
            state.selected = ordered.firstOrNull { it !in state.deferred }
            if (state.selected == null) return TaskInventory.beginReturn(state, execution, npc, state.lastProblem ?: missing(work),
                "authorized preparation sources exhausted; unknown access is not empty stock")
        }
        val position = checkNotNull(state.selected)
        if (!snapshot.onGround || TaskNavigator.distanceSquared(snapshot.position, TransportTaskDefinition.center(position)) > 9.0) {
            if (execution.approach == null && !ContainerApproachKernel.admitSelection(world)) return NpcActionResult.running("preparation approach queued")
            val approach = execution.approach ?: ContainerApproachKernel.select(world, position, snapshot.position, allowed = {
                definition.contains(NpcPosition(it.x + 0.5, it.y.toDouble(), it.z + 0.5))
            })
            if (approach == null) return defer(state, execution, npc, position, InventoryWorkReason.SOURCE_UNAVAILABLE, "no supported permitted container approach")
            execution.approach = approach
            return TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, approach, budget = definition.budget))
        }
        if (!BehaviorPlanning.admit(world, 130, PlanningKind.CONTAINER)) return NpcActionResult.running("preparation observation queued")
        val container = VisibleContainerFacts.observe(world, work.accessProbes.getValue(position))
            ?: return defer(state, execution, npc, position, InventoryWorkReason.SOURCE_UNAVAILABLE, "authorized chest is not currently visible, reachable, unlocked and fully observed")
        val block = world.observeBlock(position) ?: return defer(state, execution, npc, position, InventoryWorkReason.SOURCE_UNAVAILABLE, "container block unavailable")
        val shape = ContainerCheckpoint(block.blockId, container.containerSize)
        if (state.checkpoints[position]?.let { it != shape } == true) return defer(state, execution, npc, position, InventoryWorkReason.SOURCE_UNAVAILABLE, "container shape changed")
        state.checkpoints[position] = shape
        val matching = container.slots.filter { InventoryFacts.matches(work.query, it.stack, it.knowledge, work.minimumDurability) }
        val available = (matching.sumOf { it.stack.count } - work.sourceReserve).coerceAtLeast(0)
        val candidates = matching.filter { (state.resources.entries[it.stack.itemId]?.supplied ?: 0) < work.count }
            .map { NpcInventoryEntry(it.slot, it.stack, it.knowledge) }
        val choice = EquipmentPreparation.choose(candidates, work.query, work.destination ?: NpcEquipmentDestination.MAIN_HAND, work.minimumDurability, -1)
        if (available == 0 || choice == null) return defer(state, execution, npc, position, missing(work), "no usable matching stock above its explicit reserve")
        val item = checkNotNull(choice.stack.itemId)
        if (!state.admitPreparationItem(work, item)) return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.STEP_LIMIT, "preparation reached sixteen distinct item identities")
        val remaining = if (destination == null) work.count - facts.count(work.query, work.minimumDurability) else 1
        val quota = work.count - (state.resources.entries[item]?.supplied ?: 0)
        val requested = minOf(remaining, quota, available, choice.stack.count)
        npc.stopControl()
        val transfer = ContainerStepReservations.transfer(record.id, npc, world, position, item, requested,
            ContainerTransferDirection.WITHDRAW, containerSlot = choice.slot)
        if (transfer.problem == ContainerTransferProblem.RESERVED) return transfer.action
        state.steps++
        val receipt = transfer.observation
        if (receipt != null) {
            val problem = state.confirmTransfer(receipt, HarvestResources.inventoryCounts(npc)) ?: InventoryParentAccounting.confirm(record, npc, receipt)
            if (problem != null) return TaskInventory.mismatch(record, problem)
            state.lastProblem = null
            record.detail = "preparation: physically supplied ${receipt.moved} $item; reobserve before equipping/completing"
            return NpcActionResult.succeeded(record.detail)
        }
        if (transfer.problem == ContainerTransferProblem.UNCERTAIN) return TaskInventory.mismatch(record, transfer.action.detail)
        if (transfer.problem == ContainerTransferProblem.INVENTORY_FULL) return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.INVENTORY_FULL, transfer.action.detail)
        return defer(state, execution, npc, position, InventoryWorkReason.SOURCE_UNAVAILABLE, transfer.action.detail)
    }

    private fun missing(work: EnsureItems): InventoryWorkReason =
        if (work.destination == null) InventoryWorkReason.MISSING_RESOURCE else if (work.destination == NpcEquipmentDestination.MAIN_HAND) InventoryWorkReason.MISSING_TOOL else InventoryWorkReason.MISSING_EQUIPMENT

    private fun defer(state: InventoryWorkState, execution: TaskExecution, npc: NpcFacade, position: NpcBlockPosition,
                      reason: InventoryWorkReason, detail: String): NpcActionResult {
        state.deferred.add(position); state.selected = null; state.selectedItem = null
        if (state.lastProblem != InventoryWorkReason.SOURCE_UNAVAILABLE || reason == InventoryWorkReason.SOURCE_UNAVAILABLE) state.lastProblem = reason
        state.detail = detail.take(256); TaskInventory.resetRoute(execution, npc)
        return NpcActionResult.running("$detail; considering remaining permitted endpoints within original budget")
    }
}
