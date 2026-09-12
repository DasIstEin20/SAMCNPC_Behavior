package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ItemPickupApproach
import io.samcnpc.behavior.kernel.work.*
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*
import kotlin.math.sqrt

/** Observed edible drops and fully ripe supported bushes; no target block is destroyed. */
internal object FoodGathering {
    fun selectBerry(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FoodTaskDefinition,s: FoodTaskState): NpcActionResult {
        if (!BehaviorPlanning.admit(world,16,PlanningKind.GENERAL)) return NpcActionResult.running("food crop observation queued")
        repeat(16) {
            if (s.cursor == d.volume) return TaskFood.stop(e,npc,s)
            val position = d.scanCell(s.cursor++)
            if (!checkNotNull(d.work.area).contains(position)) return@repeat
            val block = world.observeBlockDetails(position) ?: return TaskFood.stop(e,npc,s,FoodProblem.UNOBSERVABLE)
            val growth = block.environment?.growth
            if (block.blockId != "minecraft:sweet_berry_bush" || growth?.kind != NpcPlantKind.BERRY_BUSH || growth.age != growth.maxAge) return@repeat
            if (position in s.harvested) return@repeat
            if (s.harvested.size == FoodTaskState.MAX_BUSHES) return TaskFood.stop(e,npc,s,FoodProblem.OBSERVATION_LIMIT)
            s.berry = position; s.phase = FoodPhase.BERRY
            e.rejectedWorkStances.clear(); TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("fully ripe bush selected at $position")
        }
        return NpcActionResult.running("food field scan ${s.cursor}/${d.volume}")
    }
    fun berry(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FoodTaskDefinition,s: FoodTaskState): NpcActionResult {
        val target = checkNotNull(s.berry)
        val snapshot = npc.snapshot()
        val lease = HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,target,snapshot.gameTime,record.id)
        if (lease != SpatialWorkClaimKernel.Result.Acquired && lease != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("waiting for reserved berry bush")
        if (!BehaviorPlanning.admit(world,2,PlanningKind.GENERAL)) return NpcActionResult.running("ripe-bush recheck queued")
        val before = world.observeBlockDetails(target) ?: return TaskFood.stop(e,npc,s,FoodProblem.UNOBSERVABLE)
        val growth = before.environment?.growth
        if (before.blockId != "minecraft:sweet_berry_bush" || growth?.age != 3) return TaskFood.stop(e,npc,s,FoodProblem.CHANGED)
        if (npc.inventoryContents().none { it.stack.isEmpty || it.stack.itemId == FoodTaskDefinition.BERRY_ITEM && it.stack.count < it.stack.maxStackSize }) {
            if (s.deliverable(d) <= 0) return TaskFood.stop(e,npc,s,FoodProblem.INVENTORY_FULL)
            s.cursor--; s.berry=null; s.phase=FoodPhase.DEPOSIT; TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("deliver food before the next harvest")
        }
        val approach = WorkInteractionApproach.move(record,e,npc,world,d,target)
        if (approach != null) return if (approach.status == NpcActionStatus.FAILED) TaskFood.stop(e,npc,s,FoodProblem.UNREACHABLE) else approach
        TaskNavigator.stop(e,npc)
        val action = npc.useInteractiveBlock(target)
        val after = world.observeBlockDetails(target)
        if (action.status != NpcActionStatus.SUCCEEDED || after?.blockId != before.blockId || after.environment?.growth?.age != 1) return TaskFood.stop(e,npc,s,FoodProblem.CHANGED)
        s.harvested.add(target)
        s.berry=null; s.collectionOrigin=TransportTaskDefinition.center(target); s.collectionTicks=FoodTaskState.COLLECTION_TICKS; s.phase=FoodPhase.COLLECT
        TaskInventory.resetRoute(e,npc)
        record.detail="native bush interaction confirmed; awaiting physical berries"
        return NpcActionResult.succeeded(record.detail)
    }
    fun drops(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: FoodTaskDefinition,s: FoodTaskState,settling: Boolean): NpcActionResult {
        if (settling && s.collectionTicks == 0) return settled(record,e,npc,s)
        if (!BehaviorPlanning.admit(world,1281,PlanningKind.PICKUP)) return NpcActionResult.running("edible-drop observation queued")
        val snapshot=npc.snapshot()
        val box=checkNotNull(d.work.area).bounds
        val center=if (settling) checkNotNull(s.collectionOrigin) else NpcPosition((box.min.x+box.max.x+1)/2.0,(box.min.y+box.max.y+1)/2.0,(box.min.z+box.max.z+1)/2.0)
        val radius=if (settling) 5.0 else minOf(64.0,sqrt((box.width*box.width+box.depth*box.depth+box.height*box.height).toDouble())/2.0+1)
        val observations=world.queryEntities(NpcEntityQuery(center,radius,64,typeIds=setOf("minecraft:item")))
        val allowed=HarvestWorkClaims.kernel.incidentalCollectionFilter(npc.npcUuid,record.id,d.dimensionId,center,radius,snapshot.gameTime)
        val scoped=observations.filter { it.alive && it.itemKnowledge?.edible == true && it.itemStack?.itemId in d.outputs.values && d.inWork(it.position) }
        val drops=scoped.filter { allowed(it.position) }
        if (scoped.isNotEmpty() && drops.isEmpty()) return NpcActionResult.running("eligible food is temporarily reserved by another worker")
        if (settling) s.collectionTicks--
        if (drops.isEmpty()) {
            if (observations.size == 64) return TaskFood.stop(e,npc,s,FoodProblem.OBSERVATION_LIMIT)
            if (!settling) return TaskFood.stop(e,npc,s)
            if (s.collectionTicks <= FoodTaskState.COLLECTION_TICKS-FoodTaskState.DROP_WAIT) return settled(record,e,npc,s)
            return NpcActionResult.running("waiting for native food drops to settle")
        }
        val inventory=npc.inventoryContents()
        val eligible=drops.filter { drop -> inventory.any { it.stack.isEmpty || it.stack.itemId == drop.itemStack?.itemId && it.stack.count < it.stack.maxStackSize } }
        if (eligible.isEmpty()) {
            if (s.deliverable(d) == 0) return TaskFood.stop(e,npc,s,FoodProblem.INVENTORY_FULL)
            s.phase=FoodPhase.DEPOSIT; s.collectionOrigin=null; s.collectionTicks=0; TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("food pack full; deliver authorized cargo")
        }
        val selection=ItemPickupApproach.select(snapshot.position,world,eligible) ?: return TaskFood.stop(e,npc,s,FoodProblem.UNREACHABLE)
        val claim=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,FoodTaskDefinition.cell(selection.drop.position),snapshot.gameTime,record.id)
        if (claim != SpatialWorkClaimKernel.Result.Acquired && claim != SpatialWorkClaimKernel.Result.Held) return NpcActionResult.running("waiting for fair access to selected food drop")
        val standing=selection.standing
        if (standing != null) {
            if (!d.contains(standing)) return TaskFood.stop(e,npc,s,FoodProblem.UNREACHABLE)
            if (e.approach != standing) { TaskInventory.resetRoute(e,npc); e.approach=standing }
            val movement = TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,standing,budget=d.budget))
            // Transit/yield has its own finite navigation and task budgets. It must not spend
            // the short wait for absent loot and abandon a known reachable drop mid-route.
            if (settling && movement.status in setOf(NpcActionStatus.ACCEPTED,NpcActionStatus.RUNNING)) s.collectionTicks++
            return movement
        }
        TaskNavigator.stop(e,npc)
        val current=world.observeEntity(selection.drop.uuid)
        if (current?.alive != true || current.itemKnowledge?.edible != true || current.itemStack?.itemId !in d.outputs.values || !d.inWork(current.position)) return NpcActionResult.running("food candidate changed; no pickup submitted")
        val action=npc.pickupItem(current.uuid)
        FoodAccounting.observe(record,npc)?.let { return FoodAccounting.mismatch(record,it) }
        return action
    }
    private fun settled(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FoodTaskState): NpcActionResult {
        s.collectionOrigin=null; s.collectionTicks=0; s.phase=FoodPhase.SELECT
        HarvestWorkClaims.kernel.release(npc.npcUuid,record.id); TaskInventory.resetRoute(e,npc)
        return NpcActionResult.running("food drop collection window settled")
    }
}
