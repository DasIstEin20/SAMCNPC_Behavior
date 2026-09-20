package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

enum class OperationReservationKind { HARVEST_SITE, CONTAINER_STEP }
sealed interface OperationReservationTiming {
    data class Held(val expiresAtTick: Long) : OperationReservationTiming
    data class Requested(val firstRequestTick: Long, val lastRequestTick: Long) : OperationReservationTiming
}

/** Own coordination only. scopeId is usually a task UUID; standalone work may use the NPC UUID. */
data class OperationWorkReservation(
    val kind: OperationReservationKind,
    val scopeId: UUID,
    val dimensionId: String,
    val anchor: NpcBlockPosition,
    val timing: OperationReservationTiming,
)
