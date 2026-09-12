package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Supplied source containers are the only acquisition authority in stored-food mode. */
internal object FoodStored {
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FoodTaskDefinition,s: FoodTaskState): NpcActionResult {
        val work=d.work as FoodWorkOrder.Stored
        if (s.enough(d)) { s.phase=FoodPhase.DEPOSIT; s.selectedSource=null; TaskInventory.resetRoute(e,npc); return NpcActionResult.running("required food and ration acquired") }
        // Reserve source observation and candidate selection together: two requests of the
        // same planning kind would repeatedly consume the first grant and starve the second.
        if (!BehaviorPlanning.admit(world,145,PlanningKind.CONTAINER)) return NpcActionResult.running("authorized food source observation queued")
        val snapshot=npc.snapshot()
        val positions=if (work.sources.preference == ContainerPreference.ORDERED) work.sources.positions else work.sources.positions.sortedWith(
            compareBy<NpcBlockPosition> { TaskNavigator.distanceSquared(snapshot.position,TransportTaskDefinition.center(it)) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
        var unavailable=false
        val position=s.selectedSource ?: positions.firstOrNull { p ->
            val c=world.observeBlockContainer(p)
            if (c == null || c.isTruncated || c.containerSize !in 1..64) { unavailable=true; false }
            else c.slots.any { slot -> slot.knowledge.edible && slot.stack.itemId in d.outputs.values && ContainerTransferKernel.count(c,checkNotNull(slot.stack.itemId)) > work.sourceKeep }
        }
        if (position == null) return TaskFood.stop(e,npc,s,if (unavailable) FoodProblem.UNOBSERVABLE else null)
        val block=world.observeBlock(position)
        val container=world.observeBlockContainer(position)
        if (block == null || container == null || container.isTruncated || container.containerSize !in 1..64) return retry(record,e,npc,s,TaskReason.SOURCE_UNAVAILABLE,"food source is unavailable")
        val shape=ContainerCheckpoint(block.blockId,container.containerSize)
        if (s.sourceCheckpoints[position]?.let { it != shape } == true) return retry(record,e,npc,s,TaskReason.SOURCE_UNAVAILABLE,"food source changed type or shape")
        if (position !in s.sourceCheckpoints && s.sourceCheckpoints.size >= 32) return FoodAccounting.mismatch(record,"food source history exceeds32 endpoints")
        s.sourceCheckpoints[position]=shape
        val slot=container.slots.firstOrNull { it.knowledge.edible && it.stack.itemId in d.outputs.values && ContainerTransferKernel.count(container,checkNotNull(it.stack.itemId)) > work.sourceKeep }
        if (slot == null) { s.selectedSource=null; TaskInventory.resetRoute(e,npc); return NpcActionResult.running("food source depleted to its reserve; reselecting allowed source") }
        val item=checkNotNull(slot.stack.itemId)
        if (npc.inventoryContents().none { it.stack.isEmpty || it.stack.itemId == item && it.stack.count < it.stack.maxStackSize }) {
            if (s.deliverable(d) == 0) return TaskFood.stop(e,npc,s,FoodProblem.INVENTORY_FULL)
            s.selectedSource=null; s.phase=FoodPhase.DEPOSIT; TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("food pack full; deliver before continuing withdrawal")
        }
        if (s.selectedSource == null) { s.selectedSource=position; TaskInventory.resetRoute(e,npc) }
        if (TaskNavigator.distanceSquared(snapshot.position,TransportTaskDefinition.center(position)) > 9.0 || !snapshot.onGround) {
            if (e.approach == null) {
                e.approach=ContainerApproachKernel.select(world,position,snapshot.position,allowed={ d.contains(NpcPosition(it.x+0.5,it.y.toDouble(),it.z+0.5)) })
                    ?: return retry(record,e,npc,s,TaskReason.SOURCE_UNAVAILABLE,"food source lacks a supported approach")
            }
            return TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,checkNotNull(e.approach),budget=d.budget))
        }
        npc.stopControl()
        val need=((d.quantity-s.delivered(d)-s.deliverable(d)).coerceAtLeast(0)+(d.keepFood-s.retainedFood()).coerceAtLeast(0)).coerceAtLeast(1)
        val amount=minOf(64,need,ContainerTransferKernel.count(container,item)-work.sourceKeep)
        val step=ContainerStepReservations.transfer(record.id,npc,world,position,item,amount,ContainerTransferDirection.WITHDRAW,containerSlot=slot.slot)
        if (step.problem == ContainerTransferProblem.RESERVED) return step.action
        val observation=step.observation
        if (observation == null) {
            if (step.problem == ContainerTransferProblem.UNCERTAIN) return FoodAccounting.mismatch(record,step.action.detail)
            return retry(record,e,npc,s,TaskReason.SOURCE_UNAVAILABLE,step.action.detail)
        }
        s.resources.transfer(observation,ProducedTransfer.OUTPUT_SOURCE,HarvestResources.inventoryCounts(npc),ProducedGain.STOCK)?.let { return FoodAccounting.mismatch(record,it) }
        s.knownFood.add(item)
        val row=s.withdrawals[position].orEmpty()
        s.withdrawals[position]=row+(item to ((row[item] ?: 0)+observation.moved))
        record.detail="food withdrew ${observation.moved} $item; source reserve=${work.sourceKeep}; output=${s.foodOutput()}"
        return step.action
    }
    private fun retry(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FoodTaskState,why: TaskReason,detail: String): NpcActionResult {
        s.selectedSource=null; TaskInventory.resetRoute(e,npc); record.retry(why,detail)
        return NpcActionResult.running(record.detail)
    }
}
