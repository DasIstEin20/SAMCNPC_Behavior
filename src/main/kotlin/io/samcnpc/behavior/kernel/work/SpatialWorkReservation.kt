package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcBlockPosition
import java.util.UUID

internal sealed interface SpatialWorkReservation {
    val scopeId: UUID
    val dimensionId: String
    val anchor: NpcBlockPosition
    data class Held(override val scopeId: UUID, override val dimensionId: String,
        override val anchor: NpcBlockPosition, val expiresAtTick: Long) : SpatialWorkReservation
    data class Requested(override val scopeId: UUID, override val dimensionId: String,
        override val anchor: NpcBlockPosition, val firstRequestTick: Long, val lastRequestTick: Long) : SpatialWorkReservation
}
