package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.core.api.*

/** One bounded operation; every variant uses the same clock, interruptions, cargo and amendments. */
internal object TaskFood {
    fun capture(npc: NpcFacade, d: FoodTaskDefinition): FoodTaskState? {
        if (!HarvestResources.validCounts(HarvestResources.inventoryCounts(npc))) return null
        val state = FoodTaskState(ProducedResources.capture(npc))
        for (slot in npc.inventoryContents()) {
            val id=slot.knowledge.itemId
            if (id != null && slot.knowledge.edible && d.outputs.matches(id)) state.knownFood.add(id)
        }
        return state
    }
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val d = record.active.definition as FoodTaskDefinition
        val s = checkNotNull(record.active.food)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (snapshot.dimensionId != d.dimensionId || world.dimensionId != d.dimensionId || !d.contains(snapshot.position)) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"food task left its fixed world/travel boundary; physical effects retained")
            return NpcActionResult.failed(record.detail)
        }
        FoodAccounting.observe(record,npc)?.let { return FoodAccounting.mismatch(record,it) }
        when (s.phase) {
            FoodPhase.SELECT -> {
                if (s.stop != null || s.exhausted || s.enough(d)) s.phase = FoodPhase.DEPOSIT
                else when (d.work) {
                    is FoodWorkOrder.Drops -> return FoodGathering.drops(record,e,npc,world,d,s,false)
                    is FoodWorkOrder.Berries -> return FoodGathering.selectBerry(record,e,npc,world,d,s)
                    is FoodWorkOrder.Stored -> s.phase = FoodPhase.WITHDRAW
                    is FoodWorkOrder.Hunt -> return FoodHunting.select(record,e,npc,world,d,s)
                }
            }
            FoodPhase.BERRY -> return FoodGathering.berry(record,e,npc,world,d,s)
            FoodPhase.COLLECT -> return FoodGathering.drops(record,e,npc,world,d,s,true)
            FoodPhase.HUNT -> return FoodHunting.resume(record,e,npc,d,s)
            FoodPhase.WITHDRAW -> return FoodStored.tick(record,e,npc,world,d,s)
            FoodPhase.DEPOSIT -> {
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
                if (s.deliverable(d) > 0 && s.delivered(d) < d.quantity) {
                    val edible = npc.inventoryContents().filter { it.knowledge.edible }.mapNotNull { it.stack.itemId }.toSet()
                    return ProducedDelivery.tick(record,e,npc,world,d,s,permitted={ item ->
                        if (item !in edible || item !in s.knownFood) 0 else minOf(s.resources.available(item),s.deliverable(d),d.quantity-s.delivered(d))
                    },gain=ProducedGain.STOCK)
                }
                s.selectedContainer = null
                TaskInventory.resetRoute(e,npc)
                s.phase = if (s.stop == null && !s.exhausted && !s.goal(d)) FoodPhase.SELECT else FoodPhase.RETURN
            }
            FoodPhase.RETURN -> {
                if (s.stop == null && !s.exhausted && !s.goal(d)) { s.phase=FoodPhase.SELECT; return NpcActionResult.running("updated food requirement needs more collection") }
                val destination = d.returnTo
                if (destination != null) {
                    val action = TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,destination,budget=d.budget))
                    if (action.status != NpcActionStatus.SUCCEEDED) return action
                }
                val complete = s.stop == null && s.goal(d)
                val detail = "food ${if (complete) "complete" else "partial"}; delivered=${s.delivered(d)}/${d.quantity}; retainedFood=${s.retainedFood()}/${d.keepFood}; bushes=${s.harvested.size}; hunts=${s.hunts.values.sum()}; stop=${s.stop ?: if (complete) "none" else "NO_FOOD"}"
                if (complete) record.completeActive(TaskReason.FOOD_FINISHED,detail)
                else record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,detail)
                return if (complete) NpcActionResult.succeeded(detail) else NpcActionResult.failed(detail)
            }
        }
        record.detail = "food ${s.phase}; delivered=${s.delivered(d)}/${d.quantity}; retainedFood=${s.retainedFood()}/${d.keepFood}"
        return NpcActionResult.running(record.detail)
    }
    fun stop(e: TaskExecution,npc: NpcFacade,s: FoodTaskState,why: FoodProblem? = null): NpcActionResult {
        s.stop = why
        s.exhausted = true
        s.berry = null; s.collectionOrigin = null; s.collectionTicks = 0; s.selectedSource = null
        s.phase = FoodPhase.DEPOSIT
        TaskInventory.resetRoute(e,npc)
        HarvestWorkClaims.kernel.release(npc.npcUuid)
        return NpcActionResult.running("food acquisition ended: ${why ?: "no eligible resource"}; retaining ration and delivering actual partial cargo")
    }
}
