package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

internal object InventoryContainerWork {
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView,
             definition: InventoryTaskDefinition, state: InventoryWorkState): NpcActionResult {
        val work = definition.work
        val items = if (work is CollectContainer) state.capturedItemIds else work.itemIds
        val item = items.firstOrNull { needed(work, state, npc, it) > 0 }
        if (item == null) {
            val satisfied = state.satisfied(work)
            return TaskInventory.beginReturn(state, execution, npc, if (satisfied) InventoryWorkReason.SATISFIED else InventoryWorkReason.PARTIAL,
                if (satisfied) "inventory goals observed; only actual transfers credited" else "available stock changed; captured quota not fully transferred")
        }
        if (state.selectedItem != item) {
            state.selectedItem = item; state.selected = null; state.deferred.clear(); state.lastProblem = null; TaskInventory.resetRoute(execution, npc)
        }
        val snapshot = npc.snapshot()
        if (state.selected == null) {
            if (!BehaviorPlanning.admit(world, 16, PlanningKind.CONTAINER)) return NpcActionResult.running("inventory endpoint search queued in the shared planning budget")
            val selection = select(definition, state, npc, world, item)
            if (selection.first == null) return TaskInventory.beginReturn(state, execution, npc, selection.second, "no eligible authorized inventory endpoint for $item")
            state.selected = selection.first
        }
        val selected = checkNotNull(state.selected)
        val container = observe(world, selected, state)
        if (container == null) return defer(state, execution, npc, selected, "inventory endpoint changed or became unavailable", if (withdrawing(work)) InventoryWorkReason.SOURCE_UNAVAILABLE else InventoryWorkReason.DESTINATION_UNAVAILABLE)
        if (!snapshot.onGround || TaskNavigator.distanceSquared(snapshot.position, TransportTaskDefinition.center(selected)) > 9.0) {
            if (execution.approach == null && !ContainerApproachKernel.admitSelection(world)) return NpcActionResult.running("inventory approach awaits planning admission")
            val approach = execution.approach ?: ContainerApproachKernel.select(world, selected, snapshot.position, allowed = {
                definition.contains(NpcPosition(it.x + 0.5, it.y.toDouble(), it.z + 0.5))
            })
            if (approach == null) return defer(state, execution, npc, selected, "no supported authorized inventory approach", InventoryWorkReason.RETURN_FAILED)
            execution.approach = approach
            return TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, approach, budget = definition.budget))
        }
        val withdrawing = withdrawing(work)
        val sourceReserve = if (work is SupplyStock) work.needs.first { it.itemId == item }.sourceReserve else 0
        val stock = if (withdrawing) (ContainerTransferKernel.count(container, item) - sourceReserve).coerceAtLeast(0) else 2304
        val requested = minOf(64, needed(work, state, npc, item), stock)
        if (requested == 0) return defer(state, execution, npc, selected, "authorized stock changed above its reserve", if (withdrawing) InventoryWorkReason.SOURCE_EMPTY else InventoryWorkReason.PARTIAL)
        val slot = if (!withdrawing) npc.inventoryContents().firstOrNull { it.slot != snapshot.selectedHotbarSlot && it.stack.itemId == item && it.stack.count > 0 }?.slot else null
        if (!withdrawing && slot == null) return defer(state, execution, npc, selected, "only protected equipment remains", InventoryWorkReason.PARTIAL)
        val wood = record.primary.lumberjack
        if (!withdrawing && wood != null && selected !in wood.deliveries && wood.deliveries.size >= 32) {
            return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.STEP_LIMIT, "parent recipient journal is full; no additional transfer attempted")
        }
        npc.stopControl()
        val direction = if (withdrawing) ContainerTransferDirection.WITHDRAW else ContainerTransferDirection.DEPOSIT
        val step = ContainerStepReservations.transfer(record.id, npc, world, selected, item, requested, direction, inventorySlot = slot)
        if (step.problem == ContainerTransferProblem.RESERVED) return step.action
        state.steps++
        val observation = step.observation
        if (observation != null) {
            val after = HarvestResources.inventoryCounts(npc)
            val problem = state.confirmTransfer(observation, after)
                ?: InventoryParentAccounting.confirm(record, npc, observation)
            if (problem != null) return TaskInventory.mismatch(record, problem)
            state.deferred.clear(); state.lastProblem = null
            record.detail = "inventory ${work.kind}: moved=${observation.moved}/$requested; item=$item; originalRevision=${state.revision}"
            return NpcActionResult.succeeded(record.detail)
        }
        if (step.problem == ContainerTransferProblem.UNCERTAIN) return TaskInventory.mismatch(record, step.action.detail)
        if (step.problem == ContainerTransferProblem.INVENTORY_FULL) return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.INVENTORY_FULL, step.action.detail)
        return defer(state, execution, npc, selected, step.action.detail, when (step.problem) {
            ContainerTransferProblem.STORAGE_FULL -> InventoryWorkReason.STORAGE_FULL
            ContainerTransferProblem.SOURCE_EMPTY -> InventoryWorkReason.SOURCE_EMPTY
            ContainerTransferProblem.UNAVAILABLE -> if (withdrawing) InventoryWorkReason.SOURCE_UNAVAILABLE else InventoryWorkReason.DESTINATION_UNAVAILABLE
            else -> InventoryWorkReason.REJECTED
        })
    }
    private fun needed(work: InventoryWork, state: InventoryWorkState, npc: NpcFacade, item: String): Int {
        val remaining = ((state.goals[item] ?: 0) - state.moved(work.kind, item)).coerceAtLeast(0)
        if (remaining == 0) return 0
        return when (work) {
            is CollectContainer -> remaining
            is SupplyStock -> minOf(remaining, (work.needs.first { it.itemId == item }.target - (state.resources.retained()[item] ?: 0)).coerceAtLeast(0))
            is UnloadExcess -> minOf(remaining, InventoryTaskCapture.unloadable(npc, item, work.reserves.first { it.itemId == item }.keep))
            is PickupNearby -> 0
        }
    }
    private fun select(definition: InventoryTaskDefinition, state: InventoryWorkState, npc: NpcFacade, world: NpcWorldView, item: String): Pair<NpcBlockPosition?, InventoryWorkReason> {
        val work = definition.work; val choices = checkNotNull(work.containers)
        val ordered = if (choices.preference == ContainerPreference.ORDERED) choices.positions else choices.positions.sortedWith(
            compareBy<NpcBlockPosition> { TaskNavigator.distanceSquared(npc.snapshot().position, TransportTaskDefinition.center(it)) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
        var reason = state.lastProblem ?: if (withdrawing(work)) InventoryWorkReason.SOURCE_UNAVAILABLE else InventoryWorkReason.DESTINATION_UNAVAILABLE
        for (p in ordered) {
            if (p in state.deferred) continue
            val container = observe(world, p, state) ?: continue
            if (withdrawing(work)) {
                val reserve = if (work is SupplyStock) work.needs.first { it.itemId == item }.sourceReserve else 0
                if (ContainerTransferKernel.count(container, item) > reserve) return p to InventoryWorkReason.SATISFIED
                reason = InventoryWorkReason.SOURCE_EMPTY
            } else {
                if (container.slots.any { it.stack.isEmpty || it.stack.itemId == item && it.stack.count < it.stack.maxStackSize }) return p to InventoryWorkReason.SATISFIED
                reason = InventoryWorkReason.STORAGE_FULL
            }
        }
        return null to reason
    }
    private fun observe(world: NpcWorldView, p: NpcBlockPosition, state: InventoryWorkState): NpcBlockContainerObservation? {
        val block = world.observeBlock(p) ?: return null; val container = world.observeBlockContainer(p) ?: return null
        if (container.isTruncated || container.containerSize !in 1..64) return null
        val shape = ContainerCheckpoint(block.blockId, container.containerSize)
        val previous = state.checkpoints[p]
        if (previous != null && previous != shape) return null
        state.checkpoints[p] = shape; return container
    }
    private fun withdrawing(work: InventoryWork) = work is SupplyStock || work is CollectContainer
    private fun defer(state: InventoryWorkState, execution: TaskExecution, npc: NpcFacade, p: NpcBlockPosition, detail: String, reason: InventoryWorkReason): NpcActionResult {
        state.deferred.add(p); state.selected = null; TaskInventory.resetRoute(execution, npc)
        state.detail = detail.take(256); state.lastProblem = reason; return NpcActionResult.running("$detail; considering authorized alternatives")
    }
}
