package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** One finite side-work executor for manual tasks and opt-in ordinary-work interruptions. */
internal object TaskInventory {
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val definition = record.active.definition as InventoryTaskDefinition
        val state = checkNotNull(record.active.inventory)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (snapshot.dimensionId != definition.dimensionId || world.dimensionId != definition.dimensionId) return mismatch(record, "inventory work changed dimension")
        if (!definition.contains(snapshot.position)) {
            record.endInventory(false, InventoryWorkReason.RETURN_FAILED, "inventory work left its fixed travel boundary")
            return NpcActionResult.failed(record.detail)
        }
        val problem = InventoryTaskCapture.observe(record, npc)
        if (problem != null) return mismatch(record, problem)
        if (state.phase == InventoryWorkPhase.WORK && (state.workRemaining == 0 || state.steps >= definition.maxSteps)) {
            beginReturn(state, execution, npc, if (state.workRemaining == 0) InventoryWorkReason.WORK_LIMIT else InventoryWorkReason.STEP_LIMIT,
                "inventory work reached its original limit; returning with actual partial effects")
        }
        if (state.phase == InventoryWorkPhase.RETURN) {
            val result = TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, definition.returnTo, budget = definition.budget))
            if (result.status == NpcActionStatus.SUCCEEDED && record.status == TaskStatus.RUNNING && record.active.id == execution.frameId) {
                if (state.reason == InventoryWorkReason.SATISFIED && !state.satisfied(definition.work, npc)) {
                    state.reason = InventoryWorkReason.PARTIAL; state.detail = "stock changed before return; only actual effects are confirmed"
                }
                if (definition.work is EnsureItems || definition.work is UnloadExcess && definition.work.minimumFreeSlots > 0) {
                    val facts = io.samcnpc.behavior.inventory.InventoryFacts.capture(npc)
                    val ensure = definition.work as? EnsureItems
                    state.readiness = InventoryReadiness(ensure?.let { facts.count(it.query, it.minimumDurability) } ?: 0,
                        ensure?.destination?.let { facts.equippedMatches(ensure.query, it, ensure.minimumDurability) } ?: false, facts.freeSlots)
                }
                record.endInventory(true, message = state.detail)
            }
            return result
        }
        return if (definition.work is EnsureItems) InventoryPreparationWork.tick(record, execution, npc, world, definition, state, definition.work)
            else if (definition.work is PickupNearby) InventoryPickupWork.tick(record, execution, npc, world, definition, state)
            else InventoryContainerWork.tick(record, execution, npc, world, definition, state)
    }
    internal fun beginReturn(state: InventoryWorkState, execution: TaskExecution, npc: NpcFacade, why: InventoryWorkReason, detail: String): NpcActionResult {
        state.returning(why, detail); resetRoute(execution, npc)
        return NpcActionResult.running("$why; returning to captured work position")
    }
    internal fun resetRoute(execution: TaskExecution, npc: NpcFacade) {
        TaskNavigator.stop(execution, npc)
    }
    internal fun mismatch(record: TaskRecord, detail: String): NpcActionResult {
        record.active.inventory?.resources?.uncertain = true
        record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, detail)
        return NpcActionResult.failed(detail)
    }
}
