package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcPosition

/** Shared executor input; a null source list authorizes only already carried cargo. */
internal data class CargoRoute(
    val dimensionId: String, val sources: ContainerChoices?, val destinations: ContainerChoices,
    val itemId: String, val quantity: Int, val anchor: NpcPosition, val travelRadius: Double,
    val keepAtLeast: Int, val sourceKeepAtLeast: Int, val returnTo: NpcPosition?, val budget: TaskBudget,
) {
    fun contains(position: NpcPosition): Boolean = TaskNavigator.distanceSquared(anchor, position) <= travelRadius * travelRadius
    companion object {
        fun from(definition: TaskDefinition): CargoRoute = when (definition) {
            is TransportTaskDefinition -> CargoRoute(definition.dimensionId, definition.sources, definition.destinations,
                definition.itemId, definition.quantity, definition.anchor, definition.travelRadius, definition.keepAtLeast,
                definition.sourceKeepAtLeast, definition.returnTo, definition.budget)
            is DeliveryTaskDefinition -> {
                require(definition.version == 2)
                CargoRoute(definition.dimensionId, null, ContainerChoices(listOf(definition.destination)), definition.itemId,
                    definition.quantity, checkNotNull(definition.anchor), 64.0, definition.keepAtLeast, 0, null, definition.budget)
            }
            else -> throw IllegalArgumentException("operation does not use cargo accounting")
        }
    }
}
