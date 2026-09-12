package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel

/** One actual inventory-to-container transfer per selected tick; no hidden supply policy. */
internal object TaskDelivery {
    fun capture(npc: NpcFacade, definition: DeliveryTaskDefinition): ResourceProgress? {
        val container = npc.worldView().observeBlockContainer(definition.destination) ?: return null
        if (container.isTruncated || container.containerSize !in 1..64) return null
        val initial = inventoryCount(npc, definition.itemId)
        if (initial !in 0..ResourceProgress.MAX_COUNT) return null
        val count = containerCount(container, definition.itemId)
        if (count !in 0..ResourceProgress.MAX_COUNT) return null
        val progress = ResourceProgress(initial, initial, 0, count, container.containerSize)
        progress.observedLoadGeneration = npc.inventoryLoadSnapshot()?.generation
        return progress
    }

    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = record.active.definition as DeliveryTaskDefinition
        if (definition.version >= 2) return TaskTransport.tick(record, execution, npc, world)
        val progress = checkNotNull(record.active.resources)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (world.dimensionId != definition.dimensionId || snapshot.dimensionId != definition.dimensionId) {
            record.finish(TaskStatus.FAILED, TaskReason.DIMENSION_CHANGED, "delivery is outside the task dimension")
            return NpcActionResult.failed(record.detail)
        }
        val carried = inventoryCount(npc, definition.itemId)
        val loaded = npc.inventoryLoadSnapshot()
        if (loaded != null) {
            val loadedCount = loaded.inventory.sumOf { if (it.itemId == definition.itemId) it.count else 0 }
            val loadProblem = progress.reconcileLoadedInventory(loaded.generation, loadedCount)
            if (loadProblem != null) {
                progress.observedRetained = carried
                return mismatch(record, loadProblem)
            }
        }
        val container = world.observeBlockContainer(definition.destination)
        if (container == null || container.isTruncated) {
            record.retry(TaskReason.DESTINATION_UNAVAILABLE, "selected container is unavailable; no transfer attempted")
            return NpcActionResult.running(record.detail)
        }
        val mismatch = progress.reconcile(carried, containerCount(container, definition.itemId), container.containerSize)
        if (mismatch != null) return mismatch(record, mismatch)
        if (progress.delivered >= definition.quantity) {
            record.completeActive(TaskReason.DELIVERED, "requested quantity confirmed in selected container")
            return NpcActionResult.succeeded(record.detail)
        }
        val available = (progress.retained - definition.keepAtLeast).coerceAtLeast(0)
        if (available == 0) {
            record.finish(TaskStatus.FAILED, TaskReason.MISSING_RESOURCE, "carried stock exhausted at retained reserve; no other source authorized")
            return NpcActionResult.failed(record.detail, NpcActionCode.MISSING_RESOURCE)
        }
        val center = NpcPosition(definition.destination.x + 0.5, definition.destination.y + 0.5, definition.destination.z + 0.5)
        if (TaskNavigator.distanceSquared(snapshot.position, center) > 3.0 * 3.0 || !snapshot.onGround) {
            if (execution.approach == null && !ContainerApproachKernel.admitSelection(world)) {
                record.detail = "container approach deferred by the shared planning budget"
                return NpcActionResult.running(record.detail)
            }
            val approach = execution.approach ?: ContainerApproachKernel.select(world, definition.destination, snapshot.position)
            if (approach == null) {
                record.retry(TaskReason.DESTINATION_UNAVAILABLE, "no supported nearby standing cell for the selected container")
                return NpcActionResult.running(record.detail)
            }
            execution.approach = approach
            return TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, approach, budget = definition.budget))
        }
        npc.stopControl()
        val source = npc.inventoryContents().firstOrNull { it.stack.itemId == definition.itemId && it.stack.count > 0 }
            ?: return mismatch(record, "counted stock has no actual source stack")
        // Empty slots avoid mistaking two stacks with the same item ID but different NBT for
        // merge-compatible items. Core remains responsible for the exact ItemStack predicate.
        val destination = container.slots.firstOrNull { it.stack.isEmpty }
            ?: container.slots.firstOrNull { it.stack.itemId == definition.itemId && it.stack.count < it.stack.maxStackSize }
        if (destination == null) {
            record.retry(TaskReason.STORAGE_FULL, "selected storage is full; gathered stock remains carried")
            return NpcActionResult.running(record.detail)
        }
        val count = minOf(definition.quantity - progress.delivered, available, source.stack.count)
        val step = ContainerStepReservations.transfer(record.id, npc, world, definition.destination, definition.itemId, count,
            ContainerTransferDirection.DEPOSIT, source.slot, destination.slot)
        if (step.problem == ContainerTransferProblem.RESERVED) return step.action
        if (step.problem == ContainerTransferProblem.UNCERTAIN) return mismatch(record, step.action.detail)
        val result = step.action
        val after = world.observeBlockContainer(definition.destination)
            ?: return mismatch(record, "container became unobservable after transfer; effect is uncertain")
        val actualNpc = inventoryCount(npc, definition.itemId)
        val actualContainer = containerCount(after, definition.itemId)
        if (after.containerSize != progress.containerSize || after.isTruncated) return mismatch(record, "container shape changed during transfer")
        if (result.status != NpcActionStatus.SUCCEEDED) {
            val problem = progress.reconcile(actualNpc, actualContainer, after.containerSize)
            if (problem != null) return mismatch(record, problem)
            record.retry(TaskReason.STORAGE_FULL, "Core rejected delivery: ${result.detail}")
            return result
        }
        if (!progress.confirm(actualNpc, actualContainer, count)) return mismatch(record, "transfer did not produce matching inventory/container deltas")
        if (progress.delivered == definition.quantity) record.completeActive(TaskReason.DELIVERED, "requested quantity confirmed in selected container")
        return NpcActionResult.succeeded("confirmed delivery ${progress.delivered}/${definition.quantity}; retained ${progress.retained}")
    }

    private fun mismatch(record: TaskRecord, detail: String): NpcActionResult {
        record.active.resources?.uncertain = true
        record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, detail)
        return NpcActionResult.failed(record.detail, NpcActionCode.WORLD_REJECTED)
    }

    fun inventoryCount(npc: NpcFacade, itemId: String): Int = npc.inventoryContents().sumOf { if (it.stack.itemId == itemId) it.stack.count else 0 }
    fun containerCount(container: NpcBlockContainerObservation, itemId: String): Int = container.slots.sumOf { if (it.stack.itemId == itemId) it.stack.count else 0 }
}
