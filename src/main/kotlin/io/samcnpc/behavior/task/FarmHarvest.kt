package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ItemPickupApproach
import io.samcnpc.behavior.kernel.work.*
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

internal object FarmHarvest {
    fun work(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        val target=checkNotNull(s.target); val snapshot=npc.snapshot()
        val completed=e.blockCompletion; e.blockCompletion=null
        if (completed != null) return ended(record,e,npc,world,s,completed)
        if (!BehaviorPlanning.admit(world,2,PlanningKind.GENERAL)) return NpcActionResult.running("crop maturity recheck queued")
        val block=world.observeBlockDetails(target)
        val growth=block?.environment?.growth
        if (block?.blockId != d.work.crop.blockId || growth?.kind != NpcPlantKind.CROP || growth.age != growth.maxAge) {
            val unstarted=e.blockActionId == null && target !in s.harvested && target !in s.planted
            val young=block?.blockId == d.work.crop.blockId && growth?.kind == NpcPlantKind.CROP && growth.age < growth.maxAge
            if (unstarted && (block?.isAir == true || young)) {
                // Scan facts may change while a neighbor works. Committed crop effects and
                // this NPC's required resowing still retain their strict journal checks.
                s.cycleDone.add(target); e.blockToolId=null
                TaskFarm.cellFinished(record,e,npc,s)
                return NpcActionResult.running("unstarted crop is empty or young; continuing the same finite field pass without harvest credit")
            }
            return TaskFarm.stop(record,e,npc,s,FarmProblem.CHANGED,"selected crop is no longer the supplied mature block")
        }
        val claim=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,target,snapshot.gameTime,record.id)
        if (claim != SpatialWorkClaimKernel.Result.Acquired && claim != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("waiting for fair crop access")
        if (e.blockActionId == null) {
            val approach=WorkInteractionApproach.move(record,e,npc,world,d,target)
            if (approach != null) return if (approach.status == NpcActionStatus.FAILED) TaskFarm.stop(record,e,npc,s,FarmProblem.UNREACHABLE,approach.detail) else approach
            val action=npc.startBlockBreak(target)
            if (action.status == NpcActionStatus.ACCEPTED && action.actionId != null) { e.blockActionId=action.actionId; e.blockToolId=npc.snapshot().blockBreak?.toolItemId; return action }
            return ended(record,e,npc,world,s,action)
        }
        val action=npc.continueBlockBreak()
        if (action.status == NpcActionStatus.RUNNING || action.status == NpcActionStatus.ACCEPTED) return action
        return ended(record,e,npc,world,s,action)
    }
    private fun ended(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,s: FarmTaskState,result: NpcActionResult): NpcActionResult {
        if (s.phase == FarmPhase.COLLECT) return result
        val expected=e.blockActionId
        if (expected != null && result.actionId != expected) return FarmAccounting.mismatch(record,"crop completion differs from its selected Core action")
        if (result.status != NpcActionStatus.SUCCEEDED) return TaskFarm.stop(record,e,npc,s,FarmProblem.CHANGED,"${result.code}: ${result.detail}")
        val action=FarmCompletion.confirm(record,npc,world,e.blockToolId)
        e.blockActionId=null; e.blockCompletion=null; e.blockToolId=null; TaskInventory.resetRoute(e,npc)
        return action
    }
    fun collect(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        val target=checkNotNull(s.target); val snapshot=npc.snapshot()
        val claim=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,target,snapshot.gameTime,record.id)
        if (claim != SpatialWorkClaimKernel.Result.Acquired && claim != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("waiting to collect the reserved harvest cell")
        if (s.collectionTicks == 0) return collected(record,e,npc,d,s)
        if (!BehaviorPlanning.admit(world,1281,PlanningKind.PICKUP)) return NpcActionResult.running("actual crop-drop observation queued")
        val allowed=HarvestWorkClaims.kernel.collectionFilter(npc.npcUuid,record.id,d.dimensionId,snapshot.gameTime)
        val observations=world.queryEntities(NpcEntityQuery(TransportTaskDefinition.center(target),4.0,64,typeIds=setOf("minecraft:item")))
        val drops=observations.filter { it.alive && it.itemStack?.itemId in d.work.crop.collectedItems && FarmAccounting.allowsPickup(d,s,it.position) && allowed(it.position) }
        val waitBefore = s.collectionTicks
        s.collectionTicks--
        if (s.collectionTicks == 0 && drops.isNotEmpty()) {
            com.mojang.logging.LogUtils.getLogger().warn("FARM_COLLECTION_TIMEOUT npc={} target={} position={} navigation={} drops={}",
                npc.npcUuid, target, snapshot.position, snapshot.navigation,
                drops.take(8).map { "${it.uuid}:${it.itemStack}@${it.position}" })
        }
        if (drops.isEmpty()) {
            if (observations.size == 64) return TaskFarm.stop(record,e,npc,s,FarmProblem.OBSERVATION_LIMIT,"crop drop query is saturated")
            if (s.collectionTicks <= FarmTaskState.COLLECTION_TICKS-FarmTaskState.DROP_WAIT) return collected(record,e,npc,d,s)
            return NpcActionResult.running("waiting for native crop drops")
        }
        val selected=ItemPickupApproach.select(snapshot.position,world,drops)
        if (selected == null) {
            TaskNavigator.stop(e,npc)
            return NpcActionResult.running("crop drops await supported pickup access within the original collection window")
        }
        val standing=selected.standing
        if (standing != null) {
            if (!d.contains(standing)) return TaskFarm.stop(record,e,npc,s,FarmProblem.UNREACHABLE,"crop pickup approach exceeds the travel boundary")
            val step = TaskPickupNavigation.move(record,e,npc,world,d.dimensionId,standing,d.budget,waitBefore)
            s.collectionTicks = step.collectionTicks
            return step.action
        }
        TaskNavigator.stop(e,npc)
        val action=npc.pickupItem(selected.drop.uuid)
        FarmAccounting.observe(record,npc)?.let { return FarmAccounting.mismatch(record,it) }
        return action
    }
    private fun collected(record: TaskRecord,e: TaskExecution,npc: NpcFacade,d: FarmTaskDefinition,s: FarmTaskState): NpcActionResult {
        s.collectionTicks=0
        if (d.work.mode == FarmMode.HARVEST) return TaskFarm.cellFinished(record,e,npc,s)
        s.phase=FarmPhase.SOIL; e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("harvest collection settled; replant obligation remains")
    }
}
