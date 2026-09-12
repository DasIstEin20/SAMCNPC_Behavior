package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ItemPickupApproach
import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.behavior.kernel.navigation.WorkApproachVisibility
import io.samcnpc.behavior.kernel.work.*
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*
import kotlin.math.floor

/** A block is credited only after its matching Core completion and an observed empty cell. */
internal object MiningWorkMechanics {
    fun work(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: MiningTaskDefinition,s: MiningTaskState): NpcActionResult {
        val target=checkNotNull(s.target); val snapshot=npc.snapshot()
        val completed=e.blockCompletion
        e.blockCompletion=null
        if (completed != null) return ended(record,e,npc,world,d,s,completed)
        if (!BehaviorPlanning.admit(world,8,PlanningKind.GENERAL)) return NpcActionResult.running("mining safety recheck queued")
        val block=world.observeBlockDetails(target.position) ?: return TaskMining.stop(s,e,npc,MiningProblem.UNOBSERVABLE)
        if (block.blockId != target.blockId || block.isAir) {
            if (e.blockActionId == null && block.isAir && d.work.method in setOf(MiningMethod.EXPOSED,MiningMethod.VEIN)) {
                // An unstarted read-only selection grants no removal credit. Recheck before
                // waiting for access so another worker's finished cell cannot hold our queue.
                s.target=null; s.phase=MiningPhase.SELECT; e.blockToolId=null
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id); TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("unstarted ore target is already empty; continuing the original bounded search")
            }
            return TaskMining.stop(s,e,npc,MiningProblem.CHANGED)
        }
        val lease=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,target.position,snapshot.gameTime,record.id)
        if (lease != SpatialWorkClaimKernel.Result.Acquired && lease != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("waiting for fair harvest access: $lease")
        MiningSelection.safety(block,world)?.let { return TaskMining.stop(s,e,npc,it) }
        if (e.blockActionId != null) {
            if (underminesFeet(snapshot.position,target.position)) {
                e.blockActionId=null; e.blockCompletion=null; npc.abortBlockBreak(); TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("body entered the target support column; choose a side stance before restarting the strike")
            }
            val action=npc.continueBlockBreak()
            return if (action.status == NpcActionStatus.RUNNING || action.status == NpcActionStatus.ACCEPTED) action else ended(record,e,npc,world,d,s,action)
        }
        val hasSpace=npc.inventoryContents().any { it.stack.isEmpty || it.stack.itemId in d.outputs.values && it.stack.count < it.stack.maxStackSize }
        if (!hasSpace) {
            if (s.cargo(d) > 0) { s.phase=MiningPhase.DEPOSIT; s.selection.cursor=(s.selection.cursor-1).coerceAtLeast(0); if (d.work.method == MiningMethod.VEIN && s.selection.veinAnchor != null) { s.selection.examined.remove(target.position); s.selection.frontier.addFirst(target.position) }; s.target=null; TaskInventory.resetRoute(e,npc); return NpcActionResult.running("inventory full; deliver before the next removal") }
            return TaskMining.stop(s,e,npc,MiningProblem.INVENTORY_FULL)
        }
        if (!snapshot.onGround || !standingFor(snapshot.position,snapshot.eyePosition,target.position,world,d)) {
            if (e.approach == null) {
                if (e.rejectedWorkStances.size >= 16) return TaskMining.stop(s,e,npc,MiningProblem.UNREACHABLE,"sixteen observed approach candidates failed")
                if (!ContainerApproachKernel.admitSelection(world,2)) return NpcActionResult.running("mining stance search queued")
                e.approach=ContainerApproachKernel.select(world,target.position,snapshot.position,offset=2,allowed={ cell ->
                    val feet=NpcPosition(cell.x+0.5,cell.y.toDouble(),cell.z+0.5)
                    cell !in e.rejectedWorkStances && standingFor(feet,NpcPosition(feet.x,feet.y+snapshot.eyePosition.y-snapshot.position.y,feet.z),target.position,world,d,arrivalMargin=0.3,stagingOrigin=snapshot.position)
                }) ?: return TaskMining.stop(s,e,npc,MiningProblem.UNREACHABLE,"no visible supported candidate from ${snapshot.position}")
            }
            val approach=checkNotNull(e.approach)
            val result=TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,approach,arrivalDistance=0.3,budget=d.budget))
            if (result.status == NpcActionStatus.SUCCEEDED) {
                e.rejectedWorkStances.add(NpcBlockPosition(floor(approach.x).toInt(),floor(approach.y).toInt(),floor(approach.z).toInt()))
                TaskInventory.resetRoute(e,npc)
                return NpcActionResult.running("arrived stance still lacks valid visibility/support; trying another bounded candidate")
            }
            return result
        }
        TaskInventory.resetRoute(e,npc)
        val action=npc.startBlockBreak(target.position)
        if (action.status == NpcActionStatus.ACCEPTED && action.actionId != null) { e.blockActionId=action.actionId; e.blockToolId=npc.snapshot().blockBreak?.toolItemId; return action }
        return ended(record,e,npc,world,d,s,action)
    }
    private fun standingFor(feet: NpcPosition,eye: NpcPosition,target: NpcBlockPosition,world: NpcWorldView,d: MiningTaskDefinition,arrivalMargin: Double = 0.0, stagingOrigin: NpcPosition? = null): Boolean {
        if (!d.contains(feet)) return false
        // Preserve every supporting block touched by the NPC's player-width feet.
        if (underminesFeet(feet,target,arrivalMargin)) return false
        val center=TransportTaskDefinition.center(target)
        if (TaskNavigator.distanceSquared(eye,center) > 4.0*4.0) return false
        return if (stagingOrigin == null) world.visibleBlockFrom(feet,target) == true
            else WorkApproachVisibility.allowsCandidate(world,stagingOrigin,feet,target)
    }
    private fun underminesFeet(feet: NpcPosition,target: NpcBlockPosition,margin: Double = 0.0): Boolean =
        target.y == floor(feet.y-0.01).toInt() && target.x in floor(feet.x-0.3-margin).toInt()..floor(feet.x+0.3+margin).toInt() &&
            target.z in floor(feet.z-0.3-margin).toInt()..floor(feet.z+0.3+margin).toInt()
    private fun ended(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: MiningTaskDefinition,s: MiningTaskState,result: NpcActionResult): NpcActionResult {
        val target=checkNotNull(s.target)
        val expected=e.blockActionId; e.blockActionId=null; e.blockCompletion=null
        if (expected != null && result.actionId != expected) return TaskMining.mismatch(record,"block completion differs from the active mining action")
        if (result.status != NpcActionStatus.SUCCEEDED) return TaskMining.stop(s,e,npc,if (result.code == NpcActionCode.UNSUITABLE_TOOL || result.code == NpcActionCode.MISSING_RESOURCE) MiningProblem.MISSING_TOOL else if (result.code == NpcActionCode.CONFLICT) MiningProblem.CHANGED else MiningProblem.UNREACHABLE,"${result.code}: ${result.detail}")
        val confirmed=MiningCompletion.confirm(record,npc,world,e.blockToolId)
        e.blockToolId=null; TaskInventory.resetRoute(e,npc)
        return confirmed
    }
    fun collect(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: MiningTaskDefinition,s: MiningTaskState): NpcActionResult {
        val target=checkNotNull(s.target); val snapshot=npc.snapshot()
        val lease=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,target.position,snapshot.gameTime,record.id)
        if (lease != SpatialWorkClaimKernel.Result.Acquired && lease != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("waiting to recollect the reserved work site")
        if (s.collectionTicks <= 0) {
            return finishCollection(record,e,npc,s,"bounded collection window ended")
        }
        if (!BehaviorPlanning.admit(world,641,PlanningKind.PICKUP)) return NpcActionResult.running("mining drop search queued")
        val waitBefore = s.collectionTicks
        s.collectionTicks--
        val allowed=HarvestWorkClaims.kernel.collectionFilter(npc.npcUuid,record.id,d.dimensionId,snapshot.gameTime)
        val drops=world.queryEntities(NpcEntityQuery(TransportTaskDefinition.center(target.position),4.0,32,typeIds=setOf("minecraft:item"))).filter {
            it.alive && it.itemStack?.itemId in d.outputs.values && d.contains(it.position) && allowed(it.position)
        }
        if (drops.isEmpty() && s.collectionTicks <= MiningTaskState.COLLECTION_TICKS-MiningTaskState.INITIAL_DROP_WAIT)
            return finishCollection(record,e,npc,s,"physical drop wait settled; no eligible drop remains")
        val selection=ItemPickupApproach.select(snapshot.position,world,drops) ?: return NpcActionResult.running("waiting for physical block drops")
        val standing=selection.standing
        if (standing != null) {
            if (!d.contains(standing)) return NpcActionResult.running("drop approach is outside the fixed travel boundary")
            val step = TaskPickupNavigation.move(record,e,npc,world,d.dimensionId,standing,d.budget,waitBefore)
            s.collectionTicks = step.collectionTicks
            return step.action
        }
        TaskNavigator.stop(e,npc)
        val action=npc.pickupItem(selection.drop.uuid)
        TaskMining.observeInventory(record,npc)?.let { return TaskMining.mismatch(record,it) }
        return action
    }
    private fun finishCollection(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: MiningTaskState,detail: String): NpcActionResult {
        s.collectionTicks=0; s.phase=MiningPhase.SELECT; s.target=null
        HarvestWorkClaims.kernel.release(npc.npcUuid,record.id); TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running(detail)
    }

}
