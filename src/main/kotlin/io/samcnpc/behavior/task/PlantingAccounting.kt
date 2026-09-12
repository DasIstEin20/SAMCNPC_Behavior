package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.core.api.*

internal object PlantingAccounting {
    fun observe(record: TaskRecord,npc: NpcFacade): String? {
        val state=record.primary.planting ?: return null
        val actual=HarvestResources.inventoryCounts(npc)
        return state.resources.reconcileLoad(npc.inventoryLoadSnapshot(),actual) ?: state.resources.observeLive(actual)
    }
    fun consume(record: TaskRecord,npc: NpcFacade,s: PlantingTaskState,item: String): String? {
        val actual=HarvestResources.inventoryCounts(npc); val before=s.resources.retained()
        if ((before[item] ?: 0)-(actual[item] ?: 0) != 1 || before.any { (id,count) -> id != item && (actual[id] ?: 0) < count }) return "sapling placement lacks exactly one physical item consumption"
        val problem=s.resources.observeStep(actual,emptyMap(),emptyMap(),placement=true)
        if (problem != null) return problem
        // A composed wood stage retains its own full-operation physical account too.
        return record.primary.lumberjack?.resources?.observeStep(actual,emptyMap(),emptyMap(),placement=true)
    }
    fun transfer(record: TaskRecord,npc: NpcFacade,o: ContainerTransferObservation): String? {
        val s=record.primary.planting ?: return null
        val problem=s.resources.observeStep(HarvestResources.inventoryCounts(npc),mapOf(o.itemId to o.containerBefore),mapOf(o.itemId to o.containerAfter),placement=false)
        if (problem != null) return problem
        if (o.position !in s.checkpoints && s.checkpoints.size >= 32) return "planting container history exceeds32 endpoints"
        s.checkpoints[o.position]=ContainerCheckpoint(o.blockId,o.containerSize)
        val rows=if(o.direction == ContainerTransferDirection.WITHDRAW) s.withdrawals else s.deliveries
        val row=rows[o.position].orEmpty()
        rows[o.position]=row+(o.itemId to ((row[o.itemId] ?: 0)+o.moved))
        return null
    }
    fun mismatch(record: TaskRecord,s: PlantingTaskState,detail: String): NpcActionResult {
        s.resources.uncertain=true
        record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,detail)
        return NpcActionResult.failed(detail)
    }
}
