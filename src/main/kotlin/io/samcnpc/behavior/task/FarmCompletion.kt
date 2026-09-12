package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Called immediately at the matching Core completion, before cancellation or another task decision. */
internal object FarmCompletion {
    fun confirm(record: TaskRecord,npc: NpcFacade,world: NpcWorldView,toolId: String?): NpcActionResult {
        val d=record.active.definition as FarmTaskDefinition
        val s=checkNotNull(record.active.farming)
        val target=s.target ?: return FarmAccounting.mismatch(record,"crop completion has no pending cell")
        if (s.phase != FarmPhase.HARVEST || target !in d.work.cells || target in s.cycleDone || world.observeBlock(target)?.isAir != true) return FarmAccounting.mismatch(record,"crop completion lacks its pending mature-cell removal")
        val actual=HarvestResources.inventoryCounts(npc)
        val before=s.resources.physical.retained()
        val problem=if (toolId != null && (before[toolId] ?: 0)-(actual[toolId] ?: 0) == 1)
            s.resources.consume(toolId,1,actual,ProducedGain.STOCK) else s.resources.observeLive(actual,ProducedGain.STOCK)
        if (problem != null) return FarmAccounting.mismatch(record,problem)
        val count=(s.harvested[target] ?: 0)+1
        if (count > d.work.cycles) return FarmAccounting.mismatch(record,"crop removal exceeds the supplied cycle count")
        s.harvested[target]=count; s.cycleDone.add(target)
        s.phase=FarmPhase.COLLECT; s.collectionTicks=FarmTaskState.COLLECTION_TICKS
        record.detail="mature crop removal confirmed at $target; awaiting real drops and required replant"
        return NpcActionResult.succeeded(record.detail)
    }
}
