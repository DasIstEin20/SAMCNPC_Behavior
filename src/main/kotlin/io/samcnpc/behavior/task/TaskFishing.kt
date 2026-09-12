package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ItemPickupApproach
import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.core.api.*
import kotlin.math.atan2
import kotlin.math.sqrt

/** A bounded catch/collect/return intent; the Core hook is the sole fishing effect authority. */
internal object TaskFishing {
    fun capture(npc: NpcFacade): FishingTaskState? {
        if (npc.fishingState() != null || npc.inventoryContents().none { it.stack.itemId == FishingTaskDefinition.ROD }) return null
        val counts = HarvestResources.inventoryCounts(npc)
        return if (HarvestResources.validCounts(counts)) FishingTaskState(HarvestResources.capture(npc)) else null
    }
    fun observeInventory(record: TaskRecord, npc: NpcFacade): String? {
        val state = record.primary.fishing ?: return null
        if (state.pendingReel) return "fishing payout is unconfirmed; inspect actual world/inventory before replacing this task"
        val current = HarvestResources.inventoryCounts(npc)
        return state.resources.reconcileLoad(npc.inventoryLoadSnapshot(), current) ?: state.resources.observeLive(current)
    }
    fun tick(record: TaskRecord, e: TaskExecution, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val d = record.active.definition as FishingTaskDefinition
        val s = checkNotNull(record.active.fishing)
        val snapshot = npc.snapshot()
        record.reconciledPosition = snapshot.position
        if (world.dimensionId != d.dimensionId || snapshot.dimensionId != d.dimensionId) return fail(record,TaskReason.DIMENSION_CHANGED,"fishing task left its assigned dimension")
        if (!d.contains(snapshot.position)) return fail(record,TaskReason.WORK_FAILED,"fishing task left its fixed travel boundary")
        val problem = observeInventory(record,npc) ?: s.validationProblem(d)
        if (problem != null || s.resources.uncertain) return fail(record,TaskReason.STATE_MISMATCH,problem ?: "fishing inventory is uncertain")
        if (s.phase == FishingPhase.COLLECT) return collect(record,e,npc,world,d,s)
        if (s.caught == d.catches) return finish(record,e,npc,world,d,s)
        val fishing = snapshot.fishing
        if (fishing != null) {
            if (e.fishingActionId != fishing.actionId) return fail(record,TaskReason.STATE_MISMATCH,"fishing hand belongs to a different action generation")
            s.phase = FishingPhase.WAIT
            if (fishing.phase == NpcFishingPhase.BITING) return reel(record,e,npc,s,fishing.actionId)
            val renewed = npc.continueFishing(fishing.actionId)
            if (renewed.status != NpcActionStatus.RUNNING) return retryCast(record,e,npc,s,renewed.detail)
            record.detail="fishing ${fishing.phase}; catches=${s.caught}/${d.catches}; casts=${s.casts}/${d.maximumCasts}"
            return renewed
        }
        // Pausing/combat/load invalidates the old execution. It grants neither a fresh task
        // budget nor an extra catch; collection obligations are handled before this branch.
        if (e.fishingActionId != null) {
            val oldId=checkNotNull(e.fishingActionId)
            val result=snapshot.recentCompletions.lastOrNull { it.result.actionId==oldId }?.result
            e.fishingActionId=null
            return retryCast(record,e,npc,s,result?.detail ?: "fishing hook ended before collection")
        }
        if (s.phase == FishingPhase.WAIT) s.phase=FishingPhase.APPROACH
        if (TaskNavigator.distanceSquared(snapshot.position,d.standing)>0.75*0.75 || !snapshot.onGround) {
            s.phase=FishingPhase.APPROACH
            return TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,d.standing,budget=d.budget))
        }
        TaskInventory.resetRoute(e,npc)
        val standing=world.observeStandingSpace(d.standing)
        if (standing == null || !standing.clear || !standing.supported || standing.inFluid) return retryCast(record,e,npc,s,"fishing stance became unavailable or unsafe")
        val water=world.observeBlockDetails(d.water)
        if (water?.environment?.fluidId !in setOf("minecraft:water", "minecraft:flowing_water")) return retryCast(record,e,npc,s,"supplied fishing water is missing or unobservable")
        val inventory=npc.inventoryContents()
        if (inventory.none { it.stack.isEmpty }) {
            record.retry(TaskReason.STORAGE_FULL,"fishing needs a free inventory slot before another catch")
            return NpcActionResult.running(record.detail)
        }
        if (s.casts >= d.maximumCasts) return fail(record,TaskReason.WORK_FAILED,"fishing cast attempt limit reached; confirmed catches retained")
        val rod=inventory.firstOrNull { it.stack.itemId == FishingTaskDefinition.ROD }
            ?: return fail(record,TaskReason.WORK_FAILED,"no carried vanilla fishing rod remains")
        if (npc.equipmentContents().mainHand.itemId != FishingTaskDefinition.ROD) {
            val equipped=npc.equipFromInventory(rod.slot,NpcEquipmentDestination.MAIN_HAND)
            if (equipped.status != NpcActionStatus.SUCCEEDED) return fail(record,TaskReason.WORK_FAILED,equipped.detail)
        }
        val target=TransportTaskDefinition.center(d.water)
        val dx=target.x-snapshot.eyePosition.x;val dy=target.y-snapshot.eyePosition.y;val dz=target.z-snapshot.eyePosition.z
        npc.setLookRotation(NpcLookRotation(Math.toDegrees(-atan2(dx,dz)).toFloat(),Math.toDegrees(-atan2(dy,sqrt(dx*dx+dz*dz))).toFloat()))
        s.phase=FishingPhase.CAST
        val cast=npc.castFishing(NpcFishingCast(d.water))
        s.casts++
        if (cast.status != NpcActionStatus.ACCEPTED || cast.actionId == null) return retryCast(record,e,npc,s,cast.detail)
        e.fishingActionId=cast.actionId;s.phase=FishingPhase.WAIT
        return cast
    }
    private fun reel(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FishingTaskState,id: java.util.UUID): NpcActionResult {
        val before=s.resources.entries.mapValues { it.value.gathered }
        s.pendingReel=true
        val result=npc.reelFishing(id)
        e.fishingActionId=null
        s.pendingReel=false
        if (result.action.status != NpcActionStatus.SUCCEEDED) {
            s.resources.uncertain=true
            return fail(record,TaskReason.STATE_MISMATCH,"fishing payout did not confirm; ${result.action.detail}")
        }
        if (!result.caught || result.drops.isEmpty()) return retryCast(record,e,npc,s,"bite ended without confirmed fishing drops")
        s.caught++;s.collectTicks=0;s.phase=FishingPhase.COLLECT
        for (drop in result.drops) {
            val item=checkNotNull(drop.itemId)
            s.spawned[item]=(s.spawned[item] ?: 0)+drop.count
            s.drops[item]=(s.drops[item] ?: 0)+drop.count
            s.collectionBaseline[item]=before[item] ?: 0
        }
        val problem=observeInventory(record,npc)
        if (problem != null) return fail(record,TaskReason.STATE_MISMATCH,problem)
        TaskInventory.resetRoute(e,npc)
        record.detail="confirmed catch ${s.caught}; collecting actual drops ${s.drops}"
        return NpcActionResult.running(record.detail)
    }
    private fun collect(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FishingTaskDefinition,s: FishingTaskState): NpcActionResult {
        val counts=s.resources.entries.mapValues { it.value.gathered }
        val missing=s.drops.filter { (id,amount) -> (counts[id] ?: 0)-(s.collectionBaseline[id] ?: 0)<amount }
        if (missing.isEmpty()) {
            s.collectedCatches++;s.drops.clear();s.collectionBaseline.clear();s.collectTicks=0;s.phase=FishingPhase.APPROACH
            TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("confirmed fishing drops entered physical inventory")
        }
        if (s.collectTicks >= d.pickupWaitTicks) return fail(record,TaskReason.WORK_FAILED,"confirmed catch was not collected within its original wait; missing=${missing.keys}")
        val snapshot=npc.snapshot()
        val observations=world.queryEntities(NpcEntityQuery(d.standing,12.0,64,typeIds=setOf("minecraft:item")))
        val allowed=HarvestWorkClaims.kernel.incidentalCollectionFilter(npc.npcUuid,record.id,d.dimensionId,d.standing,12.0,snapshot.gameTime)
        val drops=observations.filter { it.alive && it.itemStack?.itemId in missing && d.contains(it.position) && allowed(it.position) }
        if (!ItemPickupApproach.admitSelection(snapshot.position,world,drops)) return NpcActionResult.running("fishing pickup queued in shared planning budget")
        val selected=ItemPickupApproach.select(snapshot.position,world,drops)
            ?: return NpcActionResult.running("waiting for actual fishing drops to reach an observable safe pickup")
        val standing=selected.standing
        if (standing != null) {
            if (!d.contains(standing)) return NpcActionResult.running("fishing drop is beyond the travel boundary")
            if (e.approach!=standing) { TaskInventory.resetRoute(e,npc);e.approach=standing }
            return TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,standing,budget=d.budget))
        }
        TaskNavigator.stop(e,npc)
        val result=npc.pickupItem(selected.drop.uuid)
        val problem=observeInventory(record,npc)
        return if (problem != null) fail(record,TaskReason.STATE_MISMATCH,problem) else result
    }
    private fun retryCast(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FishingTaskState,detail: String): NpcActionResult {
        if (e.fishingActionId != null && npc.fishingState()?.actionId == e.fishingActionId) npc.cancelFishing()
        e.fishingActionId=null;s.phase=FishingPhase.APPROACH
        TaskInventory.resetRoute(e,npc)
        record.retry(TaskReason.WORK_FAILED,detail)
        return NpcActionResult.running(record.detail)
    }
    private fun finish(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FishingTaskDefinition,s: FishingTaskState): NpcActionResult {
        if (s.phase!=FishingPhase.RETURN) { s.phase=FishingPhase.RETURN;TaskInventory.resetRoute(e,npc) }
        val target=d.returnTo
        if (target!=null) {
            val moved=TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,target,budget=d.budget))
            if (moved.status!=NpcActionStatus.SUCCEEDED) return moved
        }
        TaskNavigator.stop(e,npc);s.phase=FishingPhase.DONE
        record.completeActive(TaskReason.FISHING_FINISHED,"caught and collected ${s.caught} physical fishing results, returned with carried inventory")
        return NpcActionResult.succeeded(record.detail)
    }
    private fun fail(record: TaskRecord,reason: TaskReason,detail: String): NpcActionResult {
        record.finish(TaskStatus.FAILED,reason,detail)
        return NpcActionResult.failed(detail)
    }
}
