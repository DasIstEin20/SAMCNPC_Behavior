package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.*
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Supplied field cells, real hoe/seed inventory and native Core soil/placement hooks. */
internal object FarmPlanting {
    private val tillable=SoilPreparation.tillable
    fun soil(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        val target=checkNotNull(s.target)
        if (!lease(record,npc,d,target)) return NpcActionResult.running("waiting for fair planting-cell access")
        if (!BehaviorPlanning.admit(world,4,PlanningKind.GENERAL)) return NpcActionResult.running("soil facts queued")
        val soil=NpcBlockPosition(target.x,target.y-1,target.z)
        val cell=world.observeBlock(target); val below=world.observeBlockDetails(soil)
        if (cell == null || below == null) return TaskFarm.stop(record,e,npc,s,FarmProblem.UNOBSERVABLE,"planting cell or soil is unavailable")
        if (!cell.isAir) return TaskFarm.stop(record,e,npc,s,FarmProblem.CHANGED,"pending planting cell was occupied by ${cell.blockId}")
        if (below.blockId == "minecraft:farmland") { s.phase=FarmPhase.PLANT; return NpcActionResult.running("existing farmland needs no preparation") }
        if (!d.work.prepareSoil || below.blockId !in tillable || below.environment?.fluidId != null) return TaskFarm.stop(record,e,npc,s,FarmProblem.INVALID_SOIL,"soil ${below.blockId} is not authorized for preparation")
        if ((s.tilled[target] ?: 0) >= d.work.cycles+1) return TaskFarm.stop(record,e,npc,s,FarmProblem.OBSERVATION_LIMIT,"soil changed too often within this finite farm order")
        val hoe=SoilPreparation.carriedHoe(npc)
            ?: return TaskFarm.stop(record,e,npc,s,FarmProblem.MISSING_HOE,"no actual carried hoe")
        val approach=WorkInteractionApproach.move(record,e,npc,world,d,soil)
        if (approach != null) return if (approach.status == NpcActionStatus.FAILED) TaskFarm.stop(record,e,npc,s,FarmProblem.UNREACHABLE,approach.detail) else approach
        val used=when(val result=SoilPreparation.use(npc,world,soil,hoe)) {
            is SoilPreparation.Result.EquipRejected -> return TaskFarm.stop(record,e,npc,s,FarmProblem.MISSING_HOE,result.action.detail)
            is SoilPreparation.Result.Used -> result
        }
        if(used.action.code == NpcActionCode.EFFECT_UNCERTAIN) return FarmAccounting.mismatch(record,"uncertain native soil effect; no retry: ${used.action.detail}")
        used.consumptionProblem()?.let { return FarmAccounting.mismatch(record,it) }
        val consumed=(used.before[used.itemId] ?: 0)-(used.after[used.itemId] ?: 0)
        val problem=if (consumed == 1) s.resources.consume(used.itemId,1,used.after,ProducedGain.STOCK) else s.resources.observeLive(used.after,ProducedGain.STOCK)
        if (problem != null) return FarmAccounting.mismatch(record,problem)
        if (used.action.status != NpcActionStatus.SUCCEEDED || !used.farmland) return TaskFarm.stop(record,e,npc,s,FarmProblem.INVALID_SOIL,"native hoe did not establish farmland: ${used.action.detail}")
        s.tilled[target]=(s.tilled[target] ?: 0)+1; s.phase=FarmPhase.PLANT
        TaskInventory.resetRoute(e,npc)
        return NpcActionResult.succeeded("native soil preparation confirmed")
    }
    fun plant(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        val target=checkNotNull(s.target)
        if (!lease(record,npc,d,target)) return NpcActionResult.running("waiting for fair planting-cell access")
        if ((s.planted[target] ?: 0) >= d.work.cycles+1) return TaskFarm.stop(record,e,npc,s,FarmProblem.OBSERVATION_LIMIT,"planting cell was repeatedly changed during this finite order")
        val counts=HarvestResources.inventoryCounts(npc)
        if ((counts[d.work.crop.seedId] ?: 0) <= d.work.keepSeeds) return FarmSupplies.request(record,e,npc,d,s)
        val carried=npc.inventoryContents().firstOrNull { it.stack.itemId == d.work.crop.seedId && it.stack.count > 0 }
            ?: return TaskFarm.stop(record,e,npc,s,FarmProblem.MISSING_SEEDS,"seed stock is not in carried inventory")
        val soil=NpcBlockPosition(target.x,target.y-1,target.z)
        if (!BehaviorPlanning.admit(world,3,PlanningKind.GENERAL)) return NpcActionResult.running("native planting-site check queued")
        val site=world.observePlantingSite(NpcPlantingSiteQuery(carried.slot,target))
        if (site != null && site.plantBlockId == d.work.crop.blockId && site.targetIsAir && !site.inFluid &&
            !site.canSurvive && d.work.prepareSoil && site.soilBlockId in tillable) {
            // The soil may be trampled after preparation. Reuse the same finite tilling
            // obligation, including its per-cell attempt/material limits; harvest stays committed.
            s.phase=FarmPhase.SOIL
            TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("planting soil changed; recheck authorized bounded preparation")
        }
        if (site == null || site.plantBlockId != d.work.crop.blockId || !site.targetIsAir || site.inFluid || !site.canSurvive) return TaskFarm.stop(record,e,npc,s,FarmProblem.INVALID_SOIL,"supplied crop cannot survive at the currently observed planting cell")
        val approach=WorkInteractionApproach.move(record,e,npc,world,d,soil)
        if (approach != null) return if (approach.status == NpcActionStatus.FAILED) TaskFarm.stop(record,e,npc,s,FarmProblem.UNREACHABLE,approach.detail) else approach
        val equip=npc.equipFromInventory(carried.slot,NpcEquipmentDestination.MAIN_HAND)
        if (equip.status != NpcActionStatus.SUCCEEDED) return TaskFarm.stop(record,e,npc,s,FarmProblem.MISSING_SEEDS,equip.detail)
        npc.stopControl()
        val result=npc.placeHeldBlock(NpcBlockPlacement(target),NpcHand.MAIN)
        val after=HarvestResources.inventoryCounts(npc)
        if (result.status != NpcActionStatus.SUCCEEDED) {
            s.resources.observeLive(after,ProducedGain.STOCK)?.let { return FarmAccounting.mismatch(record,it) }
            return TaskFarm.stop(record,e,npc,s,FarmProblem.INVALID_SOIL,result.detail)
        }
        s.resources.consume(d.work.crop.seedId,1,after,ProducedGain.STOCK)?.let { return FarmAccounting.mismatch(record,it) }
        val placed=world.observeBlockDetails(target)
        if (placed?.blockId != d.work.crop.blockId || placed.environment?.growth?.age != 0) return FarmAccounting.mismatch(record,"seed was spent without the expected native young crop")
        s.planted[target]=(s.planted[target] ?: 0)+1
        if ((s.harvested[target] ?: 0) > (s.replanted[target] ?: 0)) s.replanted[target]=(s.replanted[target] ?: 0)+1
        return TaskFarm.cellFinished(record,e,npc,s)
    }
    private fun lease(record: TaskRecord,npc: NpcFacade,d: FarmTaskDefinition,target: NpcBlockPosition): Boolean {
        val result=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,target,npc.snapshot().gameTime,record.id)
        return result == SpatialWorkClaimKernel.Result.Acquired || result == SpatialWorkClaimKernel.Result.Held
    }
}
