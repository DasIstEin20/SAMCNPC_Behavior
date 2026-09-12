package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.ItemPickupApproach
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** Small opportunistic detour. Core owns pickup, including native partial stack insertion. */
internal object InventoryPickupWork {
    fun candidates(record: TaskRecord, npc: NpcFacade, world: NpcWorldView, work: PickupNearby,
                   origin: NpcPosition, remaining: Int): List<NpcEntityObservation> {
        if (remaining <= 0) return emptyList()
        val snapshot = npc.snapshot()
        val allowed = io.samcnpc.behavior.lumberjack.LumberjackService.incidentalDropFilter(snapshot, record.id, work.radius)
        val inventory = npc.inventoryContents()
        return world.queryEntities(NpcEntityQuery(center = origin, radius = work.radius, limit = 32, typeIds = setOf("minecraft:item"))).filter { drop ->
            val item = drop.itemStack
            drop.alive && item != null && item.itemId in work.itemIds && item.count in 1..remaining && allowed(drop.position) &&
                TaskNavigator.distanceSquared(origin, drop.position) <= work.radius * work.radius &&
                inventory.any { it.stack.isEmpty || it.stack.itemId == item.itemId && it.stack.count < it.stack.maxStackSize }
        }
    }
    fun tick(record: TaskRecord, execution: TaskExecution, npc: NpcFacade, world: NpcWorldView,
             definition: InventoryTaskDefinition, state: InventoryWorkState): NpcActionResult {
        val work = definition.work as PickupNearby
        val remaining = work.maxItems - state.picked.values.sum()
        if (remaining == 0) return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.SATISFIED, "deliberate pickup quota reached")
        if (!BehaviorPlanning.admit(world, 641, PlanningKind.PICKUP)) return NpcActionResult.running("nearby pickup search queued in the shared planning budget")
        val drops = candidates(record, npc, world, work, definition.returnTo, remaining).filter { definition.contains(it.position) }
        if (drops.isEmpty()) return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.SATISFIED, "no eligible nearby drops within item, capacity and departure limits")
        val snapshot = npc.snapshot()
        val selection = ItemPickupApproach.select(snapshot.position, world, drops)
            ?: return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.PARTIAL, "nearby drops have no supported approach")
        val standing = selection.standing
        if (standing != null) {
            if (!definition.contains(standing) || TaskNavigator.distanceSquared(definition.returnTo, standing) > work.radius * work.radius) {
                return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.PARTIAL, "pickup approach exceeds the permitted departure")
            }
            if (execution.approach != standing) { TaskInventory.resetRoute(execution, npc); execution.approach = standing }
            return TaskNavigator.move(record, execution, npc, world, NavigateTaskDefinition(definition.dimensionId, standing, budget = definition.budget))
        }
        // Reobserve the exact stack immediately before Core; another actor may have taken it.
        val beforeDrop = world.observeEntity(selection.drop.uuid)
        val stack = beforeDrop?.itemStack
        val item = stack?.itemId
        if (beforeDrop?.alive != true || item == null || item !in work.itemIds || stack.count !in 1..remaining) return NpcActionResult.running("pickup candidate changed; no action submitted")
        npc.stopControl()
        val before = HarvestResources.inventoryCounts(npc)
        val result = npc.pickupItem(beforeDrop.uuid)
        state.steps++
        val after = HarvestResources.inventoryCounts(npc)
        val moved = (after[item] ?: 0) - (before[item] ?: 0)
        val residual = world.observeEntity(beforeDrop.uuid)?.itemStack
        val residualCount = if (residual?.itemId == item) residual.count else 0
        if (moved < 0 || moved > stack.count || moved != stack.count - residualCount ||
            (before.keys + after.keys).any { it != item && before[it] != after[it] } ||
            (result.status == NpcActionStatus.SUCCEEDED) != (moved > 0)) return TaskInventory.mismatch(record, "pickup lacks matching item-entity and inventory deltas")
        val problem = state.resources.observeLive(after) ?: PlantingAccounting.observe(record,npc) ?: FarmAccounting.observe(record, npc) ?: FoodAccounting.observe(record, npc) ?: TaskMining.observeInventory(record, npc) ?: TaskLumberjack.observeInventory(record, npc) ?: TaskTransport.observeInventory(record, npc)
        if (problem != null) return TaskInventory.mismatch(record, problem)
        if (moved > 0) {
            state.picked[item] = (state.picked[item] ?: 0) + moved
            record.detail = "pickup confirmed $moved $item; deliberate=${state.picked.values.sum()}/${work.maxItems}"
            return result
        }
        return TaskInventory.beginReturn(state, execution, npc, InventoryWorkReason.REJECTED, result.detail)
    }
}
