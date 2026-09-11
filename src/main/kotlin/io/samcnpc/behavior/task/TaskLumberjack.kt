package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.InventoryBaselineKernel
import io.samcnpc.behavior.lumberjack.LumberjackCleanupReport
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.model.LumberjackWorkSelection
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer

/** A finite parent around the existing physical wood/elevation executor, with one durable record. */
internal object TaskLumberjack {
    fun capture(npc: NpcFacade, definition: LumberjackTaskDefinition): LumberjackTaskState? {
        val container = npc.worldView().observeBlockContainer(definition.destination) ?: return null
        val counts = containerCounts(container) ?: return null
        val box = definition.area.bounds
        val center = NpcBlockPosition((box.min.x + box.max.x) / 2, box.min.y, (box.min.z + box.max.z) / 2)
        val job = LumberjackService.createJob(npc, definition.destination, emptyList(), center)
        if (job.initialWoodCounts.size > 32 || !HarvestResources.validCounts(HarvestResources.inventoryCounts(npc))) return null
        job.workSelection = LumberjackWorkSelection(definition.area, definition.wood)
        return LumberjackTaskState(job, HarvestResources.capture(npc), counts, container.containerSize).apply { reconcileWorld = false }
    }

    /** Physical pickup remains observable while paused or preempted; this method issues no actions. */
    fun observeInventory(record: TaskRecord, npc: NpcFacade): String? {
        val state = record.primary.lumberjack ?: return null
        val actual = HarvestResources.inventoryCounts(npc)
        val loadProblem = state.resources.reconcileLoad(npc.inventoryLoadSnapshot(), actual)
        if (loadProblem != null) return loadProblem
        return state.resources.observeLive(actual)
    }

    fun tick(server: MinecraftServer, record: TaskRecord, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = record.active.definition as LumberjackTaskDefinition
        val state = checkNotNull(record.active.lumberjack)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) {
            record.finish(TaskStatus.FAILED, TaskReason.DIMENSION_CHANGED, "wood task is outside its assigned dimension")
            return NpcActionResult.failed(record.detail)
        }
        val inventoryProblem = observeInventory(record, npc)
        if (inventoryProblem != null) return mismatch(record, inventoryProblem)
        val container = world.observeBlockContainer(definition.destination)
        val before = container?.let(::containerCounts)
        if (before == null) return unavailable(record, "assigned wood container is unavailable or exceeds observation bounds")
        if (container.containerSize != state.containerSize || before != state.containerCounts) return mismatch(record, "assigned container differs from the confirmed resource checkpoint")
        val workProblem = LumberjackWorkReconciliation.observePending(state, world)
        if (workProblem != null) return mismatch(record, workProblem)
        if (state.reconcileWorld) {
            when (val reconciliation = LumberjackWorkReconciliation.resume(state, world)) {
                is LumberjackWorkReconciliation.Result.Mismatch -> return mismatch(record, reconciliation.detail)
                is LumberjackWorkReconciliation.Result.Unavailable -> return unavailable(record, reconciliation.detail)
                LumberjackWorkReconciliation.Result.Pending -> return NpcActionResult.running("revalidating removed work blocks before resuming")
                LumberjackWorkReconciliation.Result.Ready -> Unit
            }
        }
        val job = state.job
        job.workSelection = LumberjackWorkSelection(definition.area, definition.wood)
        val excess = InventoryBaselineKernel.excessStacks(npc, job.initialWoodCounts, definition.wood::matches).sumOf { it.count }
        job.finishAfterCurrentTree = state.resources.potentialDelivery(definition.wood, excess) >= definition.quantity
        val guarded = LumberjackTaskGuard(npc, world, definition, state)
        val result = LumberjackService.execute(server, guarded, world, job) { uuid ->
            val other = TaskStore.forServer(server).get(uuid)
            (other != null && !other.status.terminal && other.primary.definition is LumberjackTaskDefinition) ||
                LumberjackDemoStore.forServer(server).jobFor(uuid) != null
        }
        val afterContainer = world.observeBlockContainer(definition.destination)
        val afterCounts = afterContainer?.let(::containerCounts)
        if (afterCounts == null || afterContainer.containerSize != state.containerSize) return mismatch(record, "container became unavailable during the selected work step")
        val accountingProblem = state.resources.observeStep(HarvestResources.inventoryCounts(npc), before, afterCounts, guarded.placedBlock)
        if (accountingProblem != null) return mismatch(record, accountingProblem)
        state.containerCounts = afterCounts
        val observedProblem = LumberjackWorkReconciliation.observePending(state, world)
        if (observedProblem != null) return mismatch(record, observedProblem)
        guarded.problem?.let { return mismatch(record, it) }
        if (job.scanUnavailable) return unavailable(record, "selected work column cannot be observed; scan has not advanced")
        if (job.executionFinished) {
            if (result.status != NpcActionStatus.SUCCEEDED) {
                record.finish(TaskStatus.FAILED, TaskReason.WORK_FAILED, result.detail)
            } else if (state.resources.delivered(definition.wood) < definition.quantity) {
                record.finish(TaskStatus.FAILED, TaskReason.MISSING_RESOURCE, "selected work ended below the requested net wood delivery; actual effects retained")
            } else {
                record.completeActive(TaskReason.DELIVERED, "minimum net wood delivery confirmed; current trunk work ended and cleanup reported")
            }
        }
        return result
    }

    fun suspend(state: LumberjackTaskState) {
        LumberjackService.suspendExecution(state.job.npcUuid)
        state.job.climbForwardTicks = 0
        state.job.chestApproach = null
        state.job.pillarSession?.revalidateSupports = true
        state.reconcileWorld = true
        state.reconciliationCursor = 0
        state.reconciliationBlocks = null
    }

    fun preserve(server: MinecraftServer, record: TaskRecord, world: NpcWorldView?): NpcActionResult {
        val state = record.primary.lumberjack ?: return NpcActionResult.succeeded("no wood work obligation")
        if (state.residuePreserved) return NpcActionResult.succeeded("work residue already reported")
        val result = LumberjackCleanupReport.preserve(server, state.job, world, record.reason.name)
        if (result.status == NpcActionStatus.SUCCEEDED) state.residuePreserved = true
        return result
    }

    private fun containerCounts(container: NpcBlockContainerObservation): Map<String, Int>? {
        if (container.isTruncated || container.containerSize !in 1..64) return null
        val counts = linkedMapOf<String, Int>()
        for (slot in container.slots) {
            val id = slot.stack.itemId ?: continue
            if (slot.stack.count > 0) counts[id] = (counts[id] ?: 0) + slot.stack.count
        }
        return if (HarvestResources.validCounts(counts)) java.util.Map.copyOf(counts) else null
    }

    private fun mismatch(record: TaskRecord, detail: String): NpcActionResult {
        record.primary.lumberjack?.resources?.uncertain = true
        record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, detail)
        return NpcActionResult.failed(detail, NpcActionCode.WORLD_REJECTED)
    }
    private fun unavailable(record: TaskRecord, detail: String): NpcActionResult {
        record.retry(TaskReason.DESTINATION_UNAVAILABLE, detail)
        return NpcActionResult.running(record.detail)
    }
}
