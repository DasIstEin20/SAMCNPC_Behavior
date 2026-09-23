package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*

/** Uses the same stock/return/step bounds and transaction accounting as manual inventory work. */
internal object TaskPublicInventoryOrders {
    fun definition(order: OperationInventoryOrder, budget: TaskBudget): InventoryTaskDefinition {
        val work = when (val value = order.work) {
            is OperationInventoryWork.Collect -> CollectContainer(value.source, value.maxItems)
            is OperationInventoryWork.Supply -> supply(value)
            is OperationInventoryWork.Unload -> unload(value)
            is OperationInventoryWork.Pickup -> pickup(value)
        }
        return InventoryTaskDefinition(order.dimensionId, work, order.anchor, order.returnTo,
            order.travelRadius, order.workTicks, order.maxSteps, budget, order.type.definitionVersion)
    }
    internal fun supply(value: OperationInventoryWork.Supply) = SupplyStock(value.needs.map {
        StockNeed(it.itemId, it.minimum, it.target, it.sourceReserve)
    }, TaskPublicOrders.containers(value.sources))

    internal fun unload(value: OperationInventoryWork.Unload) = UnloadExcess(value.reserves.map {
        ItemReserve(it.itemId, it.keep)
    }, TaskPublicOrders.containers(value.destinations))

    internal fun pickup(value: OperationInventoryWork.Pickup) = PickupNearby(value.itemIds, value.radius, value.maxItems)
}
