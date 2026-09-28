package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcContainerEndpoint
import io.samcnpc.core.api.NpcPosition
import java.util.UUID

/** Supported public assignment types with their stable definition IDs and versions. */
enum class OperationType(val operationId: String, val definitionVersion: Int) {
    NAVIGATE("samcnpc:navigate", 1),
    DELIVER("samcnpc:deliver", 2),
    TRANSPORT("samcnpc:transport", 1),
    MACHINE("samcnpc:machine", 1),
    FISH("samcnpc:fish", 1),
    EXPLORE("samcnpc:explore", 1),
    ATTACK("samcnpc:attack", 2),
    DEFEND("samcnpc:defend", 1),
    ATTACK_AREA("samcnpc:attack_area", 1),
    PATROL("samcnpc:patrol", 1),
    INVENTORY("samcnpc:inventory_work", 3),
    MINING("samcnpc:mine", 2),
    FARM("samcnpc:farm", 1),
    PLANTING("samcnpc:plant_trees", 1),
    FOOD("samcnpc:food", 1),
    LUMBERJACK("samcnpc:lumberjack", 2),
    FIELD_PREPARATION("samcnpc:prepare_field", 1),
}

data class OperationBudget(val ticks: Int = 6000, val attempts: Int = 3, val backoffTicks: Int = 20)

/** Null expectedPriorTaskId is valid only before the first retained task for this NPC. */
data class OperationAssignmentRequest(
    val expectedPriorTaskId: UUID?,
    val issuedTick: Long,
    val expiresTick: Long,
    val order: OperationOrder,
)

data class OperationMachinePort(val endpoint: NpcContainerEndpoint, val slot: Int, val itemId: String, val quantity: Int)

class OperationMachineFeeds(ports: List<OperationMachinePort>) {
    init { require(ports.size in 1..4) { "machine feeds require one to four ports" } }
    val ports: List<OperationMachinePort> = java.util.List.copyOf(ports)
}

/** Immutable caller-supplied intent data. Validation and execution stay behind the same gateway. */
sealed interface OperationOrder {
    val type: OperationType
    val dimensionId: String
    val budget: OperationBudget

    data class Navigate(
        override val dimensionId: String,
        val destination: NpcPosition,
        val speed: Float = 1.0F,
        val arrivalDistance: Double = 0.75,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationOrder { override val type: OperationType get() = OperationType.NAVIGATE }

    data class Deliver(
        override val dimensionId: String,
        val destination: NpcBlockPosition,
        val itemId: String,
        val quantity: Int,
        val anchor: NpcPosition,
        val keepAtLeast: Int = 0,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationOrder { override val type: OperationType get() = OperationType.DELIVER }

    data class Transport(
        override val dimensionId: String,
        val sources: OperationContainers,
        val destinations: OperationContainers,
        val itemId: String,
        val quantity: Int,
        val anchor: NpcPosition,
        val travelRadius: Double = 64.0,
        val keepAtLeast: Int = 0,
        val sourceKeepAtLeast: Int = 0,
        val returnTo: NpcPosition? = null,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationOrder { override val type: OperationType get() = OperationType.TRANSPORT }

    data class Machine(
        override val dimensionId: String,
        val feeds: OperationMachineFeeds,
        val output: OperationMachinePort,
        val anchor: NpcPosition,
        val travelRadius: Double = 32.0,
        val returnTo: NpcPosition? = null,
        val pollTicks: Int = 20,
        val noProgressTicks: Int = 1200,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationOrder { override val type: OperationType get() = OperationType.MACHINE }

    data class Fish(
        override val dimensionId: String,
        val water: NpcBlockPosition,
        val standing: NpcPosition,
        val catches: Int,
        val anchor: NpcPosition,
        val travelRadius: Double = 32.0,
        val returnTo: NpcPosition? = anchor,
        val pickupWaitTicks: Int = 240,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationOrder { override val type: OperationType get() = OperationType.FISH }

    data class Explore(
        override val dimensionId: String,
        val anchor: NpcPosition,
        val radius: Int = 32,
        val cellStep: Int = 4,
        val maxCells: Int = 32,
        val verticalRange: Int = 12,
        val chunkBudget: Int = 64,
        val heading: Int = 0,
        override val budget: OperationBudget = OperationBudget(),
    ) : OperationOrder { override val type: OperationType get() = OperationType.EXPLORE }
}
