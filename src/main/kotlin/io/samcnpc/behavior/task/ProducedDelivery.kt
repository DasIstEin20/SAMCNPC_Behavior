package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.core.api.*

/** Only output provenance is delivered; auxiliary supplies and initial stock stay protected. */
internal object ProducedDelivery {
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: ProducedTaskDefinition,s: ProducedCargoState, permitted: (String) -> Int = { s.resources.available(it) }, gain: ProducedGain = ProducedGain.OUTPUT): NpcActionResult {
        val snapshot=npc.snapshot()
        val item=d.outputs.values.firstOrNull { permitted(it) > 0 } ?: return NpcActionResult.succeeded("no produced cargo remains")
        val ordered=if (d.destinations.preference == ContainerPreference.ORDERED) d.destinations.positions else d.destinations.positions.sortedWith(
            compareBy<NpcBlockPosition> { TaskNavigator.distanceSquared(snapshot.position,TransportTaskDefinition.center(it)) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
        val position=s.selectedContainer ?: ordered.firstOrNull { p ->
            val container=world.observeBlockContainer(p)
            container != null && !container.isTruncated && container.containerSize in 1..64 && container.slots.any { it.stack.isEmpty || it.stack.itemId == item && it.stack.count < it.stack.maxStackSize }
        }
        if (position == null) { record.retry(TaskReason.STORAGE_FULL,"no authorized observable work recipient has capacity; real cargo retained"); return NpcActionResult.running(record.detail) }
        if (s.selectedContainer == null) { s.selectedContainer=position; TaskInventory.resetRoute(e,npc) }
        val block=world.observeBlock(position); val container=world.observeBlockContainer(position)
        if (block == null || container == null || container.isTruncated || container.containerSize !in 1..64) return retry(record,e,npc,s,TaskReason.DESTINATION_UNAVAILABLE,"selected work recipient is unavailable")
        val shape=ContainerCheckpoint(block.blockId,container.containerSize)
        if (s.checkpoints[position]?.let { it != shape } == true) return retry(record,e,npc,s,TaskReason.DESTINATION_UNAVAILABLE,"work recipient changed type or shape")
        if (position !in s.checkpoints && s.checkpoints.size >= 32) return mismatch(record,s,"work recipient history exceeded 32 endpoints")
        s.checkpoints[position]=shape
        if (TaskNavigator.distanceSquared(snapshot.position,TransportTaskDefinition.center(position)) > 9.0 || !snapshot.onGround) {
            if (e.approach == null) {
                if (!ContainerApproachKernel.admitSelection(world)) return NpcActionResult.running("work recipient approach queued")
                e.approach=ContainerApproachKernel.select(world,position,snapshot.position,allowed={ d.contains(NpcPosition(it.x+0.5,it.y.toDouble(),it.z+0.5)) })
                    ?: return retry(record,e,npc,s,TaskReason.DESTINATION_UNAVAILABLE,"work recipient has no supported approach")
            }
            return TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,checkNotNull(e.approach),budget=d.budget))
        }
        npc.stopControl()
        val step=ContainerStepReservations.transfer(record.id,npc,world,position,item,minOf(64,s.resources.available(item),permitted(item)),ContainerTransferDirection.DEPOSIT)
        if (step.problem == ContainerTransferProblem.RESERVED) return step.action
        val observation=step.observation
        if (observation == null) {
            if (step.problem == ContainerTransferProblem.UNCERTAIN) return mismatch(record,s,step.action.detail)
            return retry(record,e,npc,s,TaskReason.STORAGE_FULL,step.action.detail)
        }
        s.resources.transfer(observation,ProducedTransfer.DELIVERY,HarvestResources.inventoryCounts(npc),gain)?.let { return mismatch(record,s,it) }
        val row=s.deliveries[position].orEmpty()
        s.deliveries[position]=row+(item to ((row[item] ?: 0)+observation.moved))
        record.detail="work delivered ${observation.moved} $item; total=${s.delivered(d)}; retained output=${s.cargo(d)}"
        return NpcActionResult.succeeded(record.detail)
    }
    private fun retry(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: ProducedCargoState,why: TaskReason,detail: String): NpcActionResult {
        s.selectedContainer=null; TaskInventory.resetRoute(e,npc); record.retry(why,detail)
        return NpcActionResult.running(record.detail)
    }
    private fun mismatch(record: TaskRecord,s: ProducedCargoState,detail: String): NpcActionResult {
        s.resources.physical.uncertain=true; record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,detail)
        return NpcActionResult.failed(detail)
    }

}
