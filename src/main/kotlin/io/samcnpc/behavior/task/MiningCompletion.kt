package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Records an already completed Core effect immediately, without issuing another world action. */
internal object MiningCompletion {
    fun confirm(record: TaskRecord,npc: NpcFacade,world: NpcWorldView,toolId: String?): NpcActionResult {
        val definition=record.active.definition as MiningTaskDefinition
        val state=checkNotNull(record.active.mining)
        val target=state.target ?: return TaskMining.mismatch(record,"mining completion has no pending target")
        if (state.phase != MiningPhase.WORK || target.position in state.selection.removed) return TaskMining.mismatch(record,"mining completion repeats or differs from pending work")
        if (world.observeBlock(target.position)?.isAir != true) return TaskMining.mismatch(record,"successful mining action lacks observed removal")
        val actual=HarvestResources.inventoryCounts(npc)
        val before=state.resources.physical.retained()
        // Core has finished the exact strike and tool wear before publishing completion. A
        // disappearing selected tool is known consumption, not unexplained inventory loss.
        val problem=if (toolId != null && (before[toolId] ?: 0)-(actual[toolId] ?: 0) == 1)
            state.resources.consume(toolId,1,actual) else state.resources.observeLive(actual)
        if (problem != null) return TaskMining.mismatch(record,problem)
        state.selection.confirmed(definition.work,target.position,target.blockId)
        state.phase=MiningPhase.COLLECT; state.collectionTicks=MiningTaskState.COLLECTION_TICKS
        record.detail="confirmed block removal ${target.blockId} at ${target.position}"
        return NpcActionResult.succeeded(record.detail)
    }
}
