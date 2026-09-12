package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.core.api.*

/** Bounded cargo stages around the same real Core calls as carried delivery and wood work. */
internal object TaskTransport {
    fun capture(npc: NpcFacade, task: TaskDefinition): TransportTaskState? {
        val definition = CargoRoute.from(task)
        val initial = TaskDelivery.inventoryCount(npc, definition.itemId)
        if (initial !in 0..TransportLedger.MAX_COUNT) return null
        val state = TransportTaskState(TransportLedger(definition.itemId, initial, initialCargo = if (definition.sources == null) initial else 0),
            phase = if (definition.sources == null) TransportPhase.DESTINATION else TransportPhase.SOURCE)
        state.ledger.observedLoadGeneration = npc.inventoryLoadSnapshot()?.generation
        state.ledger.mustReconcileLoad = false
        return state
    }

    fun observeInventory(record: TaskRecord, npc: NpcFacade): String? {
        val ledger = record.primary.transport?.ledger ?: return null
        val current = TaskDelivery.inventoryCount(npc, ledger.itemId)
        val loaded = npc.inventoryLoadSnapshot()
        val loadCount = loaded?.inventory?.sumOf { if (it.itemId == ledger.itemId) it.count else 0 } ?: current
        return ledger.reconcileLoad(loaded?.generation, loadCount) ?: ledger.observeLive(current)
    }

    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = CargoRoute.from(record.active.definition)
        val state = checkNotNull(record.active.transport)
        val ledger = state.ledger
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) {
            record.finish(TaskStatus.FAILED, TaskReason.DIMENSION_CHANGED, "transport is outside its assigned dimension")
            return NpcActionResult.failed(record.detail)
        }
        if (!definition.contains(snapshot.position)) {
            record.finish(TaskStatus.FAILED, TaskReason.WORK_FAILED, "transport reached its fixed travel boundary; cargo retained")
            return NpcActionResult.failed(record.detail)
        }
        val inventoryProblem = observeInventory(record, npc)
        if (inventoryProblem != null) return mismatch(record, inventoryProblem)
        if (ledger.delivered >= definition.quantity) {
            val destination = definition.returnTo
            if (destination != null) {
                if (state.phase != TransportPhase.RETURN) { resetRoute(npc, execution); state.phase = TransportPhase.RETURN; state.selected = null }
                val result = TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, destination, budget = definition.budget))
                if (result.status != NpcActionStatus.SUCCEEDED) return result
            }
            record.completeActive(TaskReason.DELIVERED, "physical cargo delivery confirmed; ${ledger.describe(definition.quantity)}")
            return NpcActionResult.succeeded(record.detail)
        }
        val desired = if (ledger.deliverable(definition.keepAtLeast) > 0) TransportPhase.DESTINATION else TransportPhase.SOURCE
        if (desired == TransportPhase.SOURCE && definition.sources == null) {
            record.finish(TaskStatus.FAILED, TaskReason.MISSING_RESOURCE, "authorized carried cargo is exhausted above reserve; no supply source permitted")
            return NpcActionResult.failed(record.detail, NpcActionCode.MISSING_RESOURCE)
        }
        if (state.phase != desired) {
            state.phase = desired; state.selected = null; state.deferred.clear(); resetRoute(npc, execution)
        }
        val selected = state.selected ?: select(definition, state, snapshot, world)
        if (selected == null) {
            state.deferred.clear()
            return retry(record, state, state.lastProblem ?: if (desired == TransportPhase.SOURCE) ContainerTransferProblem.SOURCE_EMPTY else ContainerTransferProblem.STORAGE_FULL,
                "no eligible authorized ${if (desired == TransportPhase.SOURCE) "source" else "recipient"}; cargo and previous delivery credit retained")
        }
        if (state.selected == null) { state.selected = selected; resetRoute(npc, execution) }
        val container = endpoint(state, world, selected)
        if (container == null) return defer(state, npc, execution, selected, "selected endpoint is unavailable or changed type/shape")
        val center = TransportTaskDefinition.center(selected)
        if (TaskNavigator.distanceSquared(snapshot.position, center) > 9.0 || !snapshot.onGround) {
            if (execution.approach == null && !ContainerApproachKernel.admitSelection(world)) {
                record.detail = "container approach deferred by the shared planning budget; cargo checkpoint retained"
                return NpcActionResult.running(record.detail)
            }
            val approach = execution.approach ?: ContainerApproachKernel.select(world, selected, snapshot.position, allowed = {
                definition.contains(NpcPosition(it.x + 0.5, it.y.toDouble(), it.z + 0.5))
            })
            if (approach == null) {
                state.lastProblem = ContainerTransferProblem.UNAVAILABLE
                return defer(state, npc, execution, selected, "no supported permitted container approach")
            }
            execution.approach = approach
            return TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, approach, budget = definition.budget))
        }
        npc.stopControl()
        val withdraw = desired == TransportPhase.SOURCE
        val requested = if (withdraw) minOf(ledger.needed(definition.quantity, definition.keepAtLeast),
            (ContainerTransferKernel.count(container, definition.itemId) - definition.sourceKeepAtLeast).coerceAtLeast(0), 64)
            else minOf(definition.quantity - ledger.delivered, ledger.deliverable(definition.keepAtLeast), 64)
        if (requested <= 0) {
            state.lastProblem = if (withdraw) ContainerTransferProblem.SOURCE_EMPTY else ContainerTransferProblem.STORAGE_FULL
            return defer(state, npc, execution, selected, "endpoint no longer has permitted stock/capacity above reserve")
        }
        if (ledger.transferCount >= TransportLedger.MAX_TRANSFERS) {
            record.finish(TaskStatus.FAILED, TaskReason.WORK_FAILED, "transport transfer limit reached; previous physical effects retained")
            return NpcActionResult.failed(record.detail)
        }
        val direction = if (withdraw) ContainerTransferDirection.WITHDRAW else ContainerTransferDirection.DEPOSIT
        val step = ContainerStepReservations.transfer(record.id, npc, world, selected, definition.itemId, requested, direction)
        if (step.problem == ContainerTransferProblem.RESERVED) { state.lastProblem = step.problem; record.detail = step.action.detail.take(TaskRecord.MAX_DETAIL_LENGTH); return step.action }
        val observation = step.observation
        if (observation != null) {
            if (!ledger.confirm(observation)) return mismatch(record, "confirmed physical transfer is inconsistent with the cargo ledger")
            state.lastProblem = null; state.deferred.clear()
            record.detail = "${direction.name}: moved=${observation.moved}/$requested partial=${observation.partial}; delivered=${ledger.delivered}/${definition.quantity}; retained=${ledger.retained}"
            return NpcActionResult.succeeded(record.detail)
        }
        val problem = step.problem ?: ContainerTransferProblem.REJECTED
        if (problem == ContainerTransferProblem.UNCERTAIN) return mismatch(record, step.action.detail)
        state.lastProblem = problem
        if (problem == ContainerTransferProblem.INVENTORY_FULL) return retry(record, state, problem, step.action.detail)
        return defer(state, npc, execution, selected, step.action.detail)
    }

    private fun select(definition: CargoRoute, state: TransportTaskState, snapshot: NpcSnapshot, world: NpcWorldView): NpcBlockPosition? {
        val choices = (if (state.phase == TransportPhase.SOURCE) definition.sources else definition.destinations) ?: return null
        val ordered = if (choices.preference == ContainerPreference.ORDERED) choices.positions else choices.positions.sortedWith(
            compareBy<NpcBlockPosition> { TaskNavigator.distanceSquared(snapshot.position, TransportTaskDefinition.center(it)) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
        for (position in ordered) {
            if (position in state.deferred) continue
            val container = endpoint(state, world, position) ?: continue
            if (state.phase == TransportPhase.SOURCE) {
                if (ContainerTransferKernel.count(container, definition.itemId) > definition.sourceKeepAtLeast) return position
                state.lastProblem = ContainerTransferProblem.SOURCE_EMPTY
            } else {
                if (container.slots.any { it.stack.isEmpty || it.stack.itemId == definition.itemId && it.stack.count < it.stack.maxStackSize }) return position
                state.lastProblem = ContainerTransferProblem.STORAGE_FULL
            }
        }
        return null
    }
    private fun endpoint(state: TransportTaskState, world: NpcWorldView, position: NpcBlockPosition): NpcBlockContainerObservation? {
        val block = world.observeBlock(position)
        val container = world.observeBlockContainer(position)
        if (block == null || container == null || container.isTruncated || container.containerSize !in 1..64) { state.lastProblem = ContainerTransferProblem.UNAVAILABLE; return null }
        val shape = ContainerCheckpoint(block.blockId, container.containerSize)
        val previous = state.checkpoints[position]
        if (previous != null && previous != shape) { state.lastProblem = ContainerTransferProblem.UNAVAILABLE; return null }
        if (previous == null) {
            if (state.checkpoints.size >= TransportLedger.MAX_ENDPOINTS) { state.lastProblem = ContainerTransferProblem.UNAVAILABLE; return null }
            state.checkpoints[position] = shape
        }
        return container
    }
    private fun defer(state: TransportTaskState, npc: NpcFacade, execution: TaskExecution, position: NpcBlockPosition, detail: String): NpcActionResult {
        state.deferred.add(position); state.selected = null; resetRoute(npc, execution)
        return NpcActionResult.running("$detail; reconsidering only authorized alternatives")
    }
    private fun retry(record: TaskRecord, state: TransportTaskState, problem: ContainerTransferProblem, detail: String): NpcActionResult {
        state.lastProblem = problem
        record.retry(when (problem) {
            ContainerTransferProblem.SOURCE_EMPTY -> TaskReason.SOURCE_EMPTY
            ContainerTransferProblem.INVENTORY_FULL -> TaskReason.INVENTORY_FULL
            ContainerTransferProblem.STORAGE_FULL, ContainerTransferProblem.REJECTED -> TaskReason.STORAGE_FULL
            else -> TaskReason.SOURCE_UNAVAILABLE
        }, "${problem.name}: $detail")
        return NpcActionResult.running(record.detail)
    }
    private fun mismatch(record: TaskRecord, detail: String): NpcActionResult {
        record.active.transport?.ledger?.uncertain = true
        record.active.transport?.lastProblem = ContainerTransferProblem.UNCERTAIN
        record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, detail)
        return NpcActionResult.failed(record.detail)
    }
    private fun resetRoute(npc: NpcFacade, execution: TaskExecution) {
        npc.stopControl(); execution.navigationId = null; execution.completion = null; execution.approach = null
        execution.bestDistanceSquared = Double.POSITIVE_INFINITY; execution.lastProgressTick = null
    }
}
