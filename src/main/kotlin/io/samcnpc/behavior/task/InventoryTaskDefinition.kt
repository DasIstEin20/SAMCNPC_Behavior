package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcBlockPosition

internal data class StockNeed(val itemId: String, val minimum: Int, val target: Int, val sourceReserve: Int = 0)
internal data class ItemReserve(val itemId: String, val keep: Int)
internal enum class InventoryWorkKind { SUPPLY, UNLOAD, PICKUP, COLLECT, ENSURE }

/** Data-only requests; endpoint lists and item filters cannot change after validation. */
internal sealed interface InventoryWork {
    val kind: InventoryWorkKind
    val itemIds: List<String>
    val containers: ContainerChoices?
    fun validationProblem(): String?
}
internal class CollectContainer(val source: NpcBlockPosition, val maxItems: Int = 2304) : InventoryWork {
    override val kind = InventoryWorkKind.COLLECT
    // Item IDs belong to the captured runtime quota, never to a guessed caller list.
    override val itemIds: List<String> = emptyList()
    override val containers = ContainerChoices(listOf(source))
    override fun validationProblem(): String? = containers.validationProblem() ?:
        if (maxItems !in 1..2304) "collection limit must be 1..2304 items" else null
}
internal class SupplyStock(needs: List<StockNeed>, override val containers: ContainerChoices) : InventoryWork {
    val needs: List<StockNeed> = java.util.List.copyOf(needs)
    override val kind = InventoryWorkKind.SUPPLY
    override val itemIds: List<String> = java.util.List.copyOf(needs.map { it.itemId })
    override fun validationProblem(): String? = itemProblem(itemIds) ?: containers.validationProblem() ?: when {
        needs.any { it.minimum !in 1..2304 || it.target !in it.minimum..2304 || it.sourceReserve !in 0..2304 } -> "supply requires 1 <= minimum <= target <= 2304 and a bounded source reserve"
        needs.sumOf { it.target.toLong() } > 2304 -> "combined supply targets exceed 2304 items"
        else -> null
    }
}
internal class UnloadExcess(reserves: List<ItemReserve>, override val containers: ContainerChoices, val minimumFreeSlots: Int = 0) : InventoryWork {
    val reserves: List<ItemReserve> = java.util.List.copyOf(reserves)
    override val kind = InventoryWorkKind.UNLOAD
    override val itemIds: List<String> = java.util.List.copyOf(reserves.map { it.itemId })
    override fun validationProblem(): String? = itemProblem(itemIds) ?: containers.validationProblem() ?:
        if (reserves.any { it.keep !in 0..2304 } || minimumFreeSlots !in 0..36) "unload reserves must be 0..2304 and free slots 0..36" else null
}
internal class EnsureItems(
    val query: io.samcnpc.behavior.api.ItemQuery,
    val count: Int,
    override val containers: ContainerChoices?,
    val minimumDurability: Double = 0.0,
    val destination: io.samcnpc.core.api.NpcEquipmentDestination? = null,
    val sourceReserve: Int = 0,
) : InventoryWork {
    override val kind = InventoryWorkKind.ENSURE
    override val itemIds: List<String> = emptyList()
    // Build stock probes at the document/assignment boundary, never parse IDs on a tick.
    val accessProbes = containers?.accessProbes.orEmpty()
    override fun validationProblem(): String? = containers?.validationProblem() ?: when {
        count !in 1..64 || sourceReserve !in 0..2304 -> "ensure count must be 1..64 and source reserve 0..2304"
        !minimumDurability.isFinite() || minimumDurability !in 0.0..1.0 -> "invalid minimum durability fraction"
        destination != null && count != 1 -> "equipment preparation requires count=1"
        else -> null
    }
    fun satisfied(npc: io.samcnpc.core.api.NpcFacade): Boolean {
        val facts = io.samcnpc.behavior.inventory.InventoryFacts.capture(npc)
        return facts.count(query, minimumDurability) >= count && (destination == null || facts.equippedMatches(query, destination, minimumDurability))
    }
}
internal class PickupNearby(itemIds: List<String>, val radius: Double = 4.0, val maxItems: Int = 32) : InventoryWork {
    override val itemIds: List<String> = java.util.List.copyOf(itemIds)
    override val kind = InventoryWorkKind.PICKUP
    override val containers: ContainerChoices? = null
    override fun validationProblem(): String? = itemProblem(itemIds) ?: when {
        !radius.isFinite() || radius !in 1.0..8.0 -> "pickup departure radius must be 1..8 blocks"
        maxItems !in 1..256 -> "deliberate pickup limit must be 1..256 items"
        else -> null
    }
}
private fun itemProblem(ids: List<String>): String? = when {
    ids.size !in 1..16 || ids.distinct().size != ids.size -> "inventory work requires 1..16 distinct item IDs"
    !HarvestResources.validCounts(ids.associateWith { 1 }) -> "invalid inventory item ID"
    else -> null
}

internal data class InventoryTaskDefinition(
    override val dimensionId: String,
    val work: InventoryWork,
    val anchor: NpcPosition,
    val returnTo: NpcPosition = anchor,
    val travelRadius: Double = 16.0,
    val workTicks: Int = 600,
    val maxSteps: Int = 128,
    override val budget: TaskBudget = TaskBudget(ticks = 1200),
    override val version: Int = 2,
) : TaskDefinition {
    override val operationId = ID
    fun contains(position: NpcPosition): Boolean = TaskNavigator.distanceSquared(anchor, position) <= travelRadius * travelRadius
    override fun validationProblem(): String? = when {
        version !in 1..3 || version == 1 && work is CollectContainer || version < 3 &&
            (work is EnsureItems || work is UnloadExcess && work.minimumFreeSlots > 0) -> "unsupported inventory operation version"
        !travelRadius.isFinite() || travelRadius !in 4.0..64.0 -> "inventory travel radius must be 4..64"
        workTicks !in 20..36000 || workTicks > budget.ticks - 20 -> "inventory work must leave at least 20 ticks for its physical return"
        maxSteps !in 1..128 -> "inventory action step limit must be 1..128"
        !contains(returnTo) -> "inventory return is outside its fixed boundary"
        work.containers?.positions.orEmpty().any { !contains(TransportTaskDefinition.center(it)) } -> "inventory container is outside its fixed boundary"
        else -> work.validationProblem() ?: NavigateTaskDefinition(dimensionId, anchor, budget = budget).validationProblem() ?:
            NavigateTaskDefinition(dimensionId, returnTo, budget = budget).validationProblem()
    }
    companion object { const val ID = "samcnpc:inventory_work" }
}
