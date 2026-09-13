package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

enum class OperationQuantityMode { TOTAL, ADD }
enum class OperationContainerPreference { ORDERED, NEAREST }

/** Copy a bounded allow-list before it can cross an asynchronous integration boundary. */
class OperationContainers(
    positions: List<NpcBlockPosition>,
    val preference: OperationContainerPreference = OperationContainerPreference.ORDERED,
) {
    init { require(positions.size in 1..8 && positions.distinct().size == positions.size) { "container choices require 1..8 distinct positions" } }
    val positions: List<NpcBlockPosition> = java.util.List.copyOf(positions)
}

sealed interface OperationChange {
    data class Quantity(val amount: Int, val mode: OperationQuantityMode = OperationQuantityMode.TOTAL) : OperationChange
    data class Recipients(val containers: OperationContainers) : OperationChange
    data class Sources(val containers: OperationContainers?) : OperationChange
    data class ExtendTime(val ticks: Int) : OperationChange
}

data class OperationAmendmentRequest(
    val taskId: UUID,
    val requestId: UUID,
    val expectedDefinitionRevision: Int,
    val issuedTick: Long,
    val expiresTick: Long,
    val change: OperationChange,
)

enum class OperationAmendmentOutcome { PENDING, APPLIED, REJECTED, EXPIRED }

/** Actual recorded outcome for this exact request payload, never inferred from task state. */
data class OperationAmendmentSnapshot(
    val requestId: UUID,
    val taskId: UUID,
    val revision: Int,
    val outcome: OperationAmendmentOutcome,
    val detail: String,
)
