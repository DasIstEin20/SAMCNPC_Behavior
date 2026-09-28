package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

internal sealed interface InventoryCaptureResult {
    data class Captured(val state: InventoryWorkState) : InventoryCaptureResult
    data class Rejected(val code: String) : InventoryCaptureResult
}

internal object InventoryTaskCapture {
    fun capture(npc: NpcFacade, definition: InventoryTaskDefinition, revision: Int, parent: TaskRecord? = null): InventoryCaptureResult {
        val resources = HarvestResources.capture(npc)
        val counts = resources.retained()
        val goals = linkedMapOf<String, Int>()
        var available = 2304
        var checkpoint: ContainerCheckpoint? = null
        when (val work = definition.work) {
            is EnsureItems -> Unit // Candidates are admitted only after physical access and authoritative observation.
            is CollectContainer -> {
                val world = npc.worldView()
                val block = world.observeBlock(work.source) ?: return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_UNAVAILABLE")
                if (block.position != work.source || !block.hasContainer)
                    return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_UNAVAILABLE")
                val container = world.observeBlockContainer(work.source) ?: return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_UNAVAILABLE")
                if (container.position != work.source || container.containerSize !in 1..64 ||
                    container.slots.size != container.containerSize ||
                    container.slots.map { it.slot }.toSet() != (0 until container.containerSize).toSet())
                    return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_INCOMPLETE")
                for (slot in container.slots.sortedBy { it.slot }) {
                    if (slot.stack.isEmpty) {
                        if (slot.stack.count != 0) return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_INVALID")
                        continue
                    }
                    val id = slot.stack.itemId ?: return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_INVALID")
                    if (slot.stack.count <= 0) return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_INVALID")
                    val count = (goals[id] ?: 0).toLong() + slot.stack.count
                    if (count !in 1..work.maxItems.toLong()) return InventoryCaptureResult.Rejected("COLLECTION_ITEM_LIMIT")
                    goals[id] = count.toInt()
                    if (goals.size > 16 || goals.values.sumOf { it.toLong() } > work.maxItems)
                        return InventoryCaptureResult.Rejected("COLLECTION_ITEM_LIMIT")
                }
                if (!HarvestResources.validCounts(goals)) return InventoryCaptureResult.Rejected("COLLECTION_SOURCE_INVALID")
                checkpoint = ContainerCheckpoint(block.blockId, container.containerSize)
            }
            is SupplyStock -> for (need in work.needs) {
                val count = counts[need.itemId] ?: 0
                if (count < need.minimum) { val goal = minOf(need.target - count, available); if (goal > 0) goals[need.itemId] = goal; available -= goal }
            }
            is UnloadExcess -> for (reserve in work.reserves) {
                if (protected(parent, reserve.itemId, npc)) continue
                val goal = minOf(unloadable(npc, reserve.itemId, reserve.keep), available)
                if (goal > 0) goals[reserve.itemId] = goal
                available -= goal
            }
            is PickupNearby -> Unit
        }
        val state = InventoryWorkState(resources, goals, revision, definition.workTicks)
        if (checkpoint != null) state.checkpoints[(definition.work as CollectContainer).source] = checkpoint
        return InventoryCaptureResult.Captured(state)
    }
    fun protected(record: TaskRecord?, item: String, npc: NpcFacade? = null): Boolean {
        val query = record?.logistics?.policy?.preparation?.query
        if (query != null && npc != null) {
            val known = npc.equipmentKnowledge()
            val facts = npc.inventoryContents().map { it.knowledge } + listOf(known.mainHand, known.offHand, known.head, known.chest, known.legs, known.feet, known.ammunition, known.totem)
            if (facts.any { it.itemId == item && query.matches(NpcItemStackSnapshot(item, 1, 64, 0, 0), it) }) return true
        }
        return when (val parent = record?.primary?.definition) {
        is DeliveryTaskDefinition -> parent.itemId == item
        is TransportTaskDefinition -> parent.itemId == item
        is PlantingTaskDefinition -> item == parent.work.species.blockId
        is FarmTaskDefinition -> item in parent.work.crop.collectedItems
        is FoodTaskDefinition -> parent.outputs.matches(item)
        is MiningTaskDefinition -> parent.outputs.matches(item)
        is LumberjackTaskDefinition -> parent.wood.matches(item) || item == parent.replant?.work?.species?.blockId
        else -> false
    }
    }
    /** The selected hand aliases one inventory slot; all separately equipped stores stay untouched. */
    fun unloadable(npc: NpcFacade, item: String, reserve: Int): Int {
        val selected = npc.snapshot().selectedHotbarSlot
        val carried = npc.inventoryContents().sumOf { if (it.slot != selected && it.stack.itemId == item) it.stack.count else 0 }
        val total = HarvestResources.inventoryCounts(npc)[item] ?: 0
        return minOf(carried, (total - reserve).coerceAtLeast(0))
    }
    fun observe(record: TaskRecord, npc: NpcFacade): String? {
        for (frame in record.frames) {
            val state = frame.inventory ?: continue
            val counts = HarvestResources.inventoryCounts(npc)
            val problem = state.resources.reconcileLoad(npc.inventoryLoadSnapshot(), counts) ?: state.resources.observeLive(counts)
            if (problem != null) return problem
        }
        return null
    }
}
