package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.*
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

internal object PlantingPlacement {
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: PlantingTaskDefinition,s: PlantingTaskState): NpcActionResult {
        val base=checkNotNull(s.selected); var plot=checkNotNull(s.plots[base])
        if (!BehaviorPlanning.admit(world,PlantingSites.cost(d.work.species)+4,PlanningKind.GENERAL)) return NpcActionResult.running("planting layout recheck queued")
        val unstarted=plot.placed.isEmpty()
        val observed=PlantingSites.observe(world,d.work,base,if (unstarted) null else plot)
        val problem=observed.problem
        if (problem != null) {
            if (unstarted && (problem == PlantingProblem.ALREADY_PLANTED || problem == PlantingProblem.GROWN_TREE)) {
                s.plots.remove(base); s.skipped[base]=problem; s.selected=null; s.phase=PlantingPhase.SELECT
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id); TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("unstarted layout was completed elsewhere; searching the remaining supplied sites without placement credit")
            }
            return TaskPlanting.stop(e,npc,s,problem,observed.detail)
        }
        if (unstarted && observed.initial != plot.initial) {
            // Only an untouched gap plan may adopt new existing members. After our first
            // placement its original footprint and actual item-consumption journal are fixed.
            plot=PlantingPlot(base,observed.initial); s.plots[base]=plot
        }
        val lease=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,base,npc.snapshot().gameTime,record.id)
        if (lease != SpatialWorkClaimKernel.Result.Acquired && lease != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("planting layout waits for fair work access")
        val missing=d.work.species.footprint(base).filter { it !in plot.initial && it !in plot.placed }
        if (missing.isEmpty()) return PlantingAccounting.mismatch(record,s,"completed layout remained pending")
        val carried=HarvestResources.inventoryCounts(npc)[d.work.species.blockId] ?: 0
        // Admit a whole remaining layout before starting it, retaining any genuine partial work.
        if (carried < d.work.keepSaplings+missing.size) return PlantingSupplies.request(record,e,npc,d,s)
        val target=missing.first(); val soil=NpcBlockPosition(target.x,target.y-1,target.z)
        val approach=WorkInteractionApproach.move(record,e,npc,world,d,soil)
        if (approach != null) return if (approach.status == NpcActionStatus.FAILED) TaskPlanting.stop(e,npc,s,PlantingProblem.UNREACHABLE,approach.detail) else approach
        val stack=npc.inventoryContents().firstOrNull { it.stack.itemId == d.work.species.blockId && it.stack.count > 0 }
            ?: return TaskPlanting.stop(e,npc,s,PlantingProblem.MISSING_SAPLINGS,"no carried sapling slot")
        val equip=npc.equipFromInventory(stack.slot,NpcEquipmentDestination.MAIN_HAND)
        if (equip.status != NpcActionStatus.SUCCEEDED) return TaskPlanting.stop(e,npc,s,PlantingProblem.MISSING_SAPLINGS,equip.detail)
        val site=world.observePlantingSite(NpcPlantingSiteQuery(npc.snapshot().selectedHotbarSlot,target))
        if (site == null || !site.targetIsAir || site.inFluid || !site.canSurvive || site.plantBlockId != d.work.species.blockId) return TaskPlanting.stop(e,npc,s,PlantingProblem.INVALID_SOIL,"native sapling survival rejected the actual supplied site")
        npc.stopControl()
        val action=npc.placeHeldBlock(NpcBlockPlacement(target),NpcHand.MAIN)
        if (action.status != NpcActionStatus.SUCCEEDED) {
            PlantingAccounting.observe(record,npc)?.let { return PlantingAccounting.mismatch(record,s,it) }
            return TaskPlanting.stop(e,npc,s,PlantingProblem.OBSTRUCTED,action.detail)
        }
        PlantingAccounting.consume(record,npc,s,d.work.species.blockId)?.let { return PlantingAccounting.mismatch(record,s,it) }
        if (world.observeBlock(target)?.blockId != d.work.species.blockId || !plot.placed.add(target)) return PlantingAccounting.mismatch(record,s,"sapling consumption lacks a unique observed placement")
        s.detail="actual sapling placed at $target; layout=${plot.initial.size+plot.placed.size}/${d.work.species.layoutSize*d.work.species.layoutSize}"
        e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc)
        if (plot.complete(d.work.species)) { s.selected=null; s.phase=PlantingPhase.SELECT; HarvestWorkClaims.kernel.release(npc.npcUuid,record.id) }
        return NpcActionResult.running(s.detail)
    }
}
