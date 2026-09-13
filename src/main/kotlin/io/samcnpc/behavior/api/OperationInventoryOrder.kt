package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcPosition

data class OperationStockNeed(val itemId: String, val minimum: Int, val target: Int, val sourceReserve: Int = 0)
data class OperationItemReserve(val itemId: String, val keep: Int)

/** Each request owns a finite immutable list; semantic checks use the established validator. */
sealed interface OperationInventoryWork {
    class Supply(needs: List<OperationStockNeed>, val sources: OperationContainers) : OperationInventoryWork {
        init { require(needs.size in 1..16) { "supply requires one to sixteen needs" } }
        val needs: List<OperationStockNeed> = java.util.List.copyOf(needs)
    }
    class Unload(reserves: List<OperationItemReserve>, val destinations: OperationContainers) : OperationInventoryWork {
        init { require(reserves.size in 1..16) { "unload requires one to sixteen reserves" } }
        val reserves: List<OperationItemReserve> = java.util.List.copyOf(reserves)
    }
    class Pickup(itemIds: List<String>, val radius: Double = 4.0, val maxItems: Int = 32) : OperationInventoryWork {
        init { require(itemIds.size in 1..16) { "pickup requires one to sixteen item IDs" } }
        val itemIds: List<String> = java.util.List.copyOf(itemIds)
    }
}

data class OperationInventoryOrder(
    override val dimensionId: String,
    val work: OperationInventoryWork,
    val anchor: NpcPosition,
    val returnTo: NpcPosition = anchor,
    val travelRadius: Double = 16.0,
    val workTicks: Int = 600,
    val maxSteps: Int = 128,
    override val budget: OperationBudget = OperationBudget(ticks = 1200),
) : OperationOrder { override val type: OperationType get() = OperationType.INVENTORY }
