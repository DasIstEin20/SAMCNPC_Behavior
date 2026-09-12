package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** One field plane and bounded scan slices. Young plants are observed, never cleared as obstacles. */
internal object FarmSelection {
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        if (!BehaviorPlanning.admit(world,16,PlanningKind.GENERAL)) return NpcActionResult.running("crop selection queued")
        val preparing=s.phase == FarmPhase.PREPARE
        repeat(16) {
            if (s.cursor == d.work.cells.size) return endPass(record,e,npc,d,s,preparing)
            val position=d.work.cells[s.cursor]
            if (!preparing && position in s.cycleDone) { s.cursor++; return@repeat }
            val block=world.observeBlockDetails(position) ?: return TaskFarm.stop(record,e,npc,s,FarmProblem.UNOBSERVABLE,"field cell is unavailable")
            if (preparing) {
                s.cursor++
                if (block.blockId == d.work.crop.blockId) return@repeat
                if (!block.isAir) return TaskFarm.stop(record,e,npc,s,FarmProblem.FOREIGN_BLOCK,"field preparation cannot replace ${block.blockId}")
                s.preparing=true; s.target=position; s.phase=FarmPhase.SOIL
                e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("preparing authorized empty crop cell")
            }
            if (d.work.mode == FarmMode.REPLANT && s.cycle > 0 && position !in s.harvested) { s.cursor++; s.cycleDone.add(position); return@repeat }
            val growth=block.environment?.growth
            if (block.blockId == d.work.crop.blockId && growth?.kind == NpcPlantKind.CROP && growth.age == growth.maxAge) {
                val space=npc.inventoryContents().any { it.stack.isEmpty || it.stack.itemId in d.work.crop.collectedItems && it.stack.count < it.stack.maxStackSize }
                if (!space) {
                    if (s.deliverable(d) == 0) return TaskFarm.stop(record,e,npc,s,FarmProblem.INVENTORY_FULL,"no room for physical crop drops")
                    s.phase=FarmPhase.DEPOSIT; TaskInventory.resetRoute(e,npc)
                    return NpcActionResult.running("deliver actual yield before another crop harvest")
                }
                s.cursor++; s.preparing=false; s.target=position; s.phase=FarmPhase.HARVEST
                e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("mature ${d.work.crop} selected at $position")
            }
            s.cursor++
            if (d.work.mode == FarmMode.CULTIVATE) {
                if (block.isAir) { s.preparing=false; s.target=position; s.phase=FarmPhase.SOIL; e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc); return NpcActionResult.running("empty crop cell needs sowing") }
                if (block.blockId != d.work.crop.blockId || growth?.kind != NpcPlantKind.CROP) return TaskFarm.stop(record,e,npc,s,FarmProblem.FOREIGN_BLOCK,"field no longer contains the supplied crop")
            } else if (d.work.mode == FarmMode.REPLANT && s.cycle > 0 && position in s.harvested) {
                if (block.blockId != d.work.crop.blockId || growth?.kind != NpcPlantKind.CROP) return TaskFarm.stop(record,e,npc,s,FarmProblem.CHANGED,"replanted crop changed before the next cycle")
            } else s.cycleDone.add(position)
        }
        return NpcActionResult.running("crop scan ${s.cursor}/${d.work.cells.size}; actual harvests=${s.totalHarvests()}")
    }
    private fun endPass(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: FarmTaskDefinition,s: FarmTaskState,preparing: Boolean): NpcActionResult {
        if (preparing) { s.preparing=false; s.cursor=0; s.phase=FarmPhase.SELECT; return NpcActionResult.running("initial field preparation complete") }
        if (s.cycleDone.size < d.work.cells.size) {
            s.phase=FarmPhase.WAIT_GROWTH; s.nextGrowthCheck=d.work.growthCheckTicks
            TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("young field cells await bounded growth")
        }
        s.cycle++
        if (s.cycle >= d.work.cycles || s.delivered(d)+s.deliverable(d) >= d.quantity) {
            s.exhausted=true; s.phase=FarmPhase.DEPOSIT; TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("finite field cycle ended; deliver actual yield")
        }
        s.cursor=0; s.cycleDone.clear(); s.growthRemaining=d.work.growthWaitTicks; s.nextGrowthCheck=d.work.growthCheckTicks; s.phase=FarmPhase.WAIT_GROWTH
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("next finite crop cycle waits for actual growth")
    }
}
