package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.kernel.inventory.ContainerStepReservations
import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.kernel.work.SpatialWorkReservation
import java.util.UUID

internal object TaskReservationInspections {
    fun capture(npcUuid: UUID, tick: Long): List<OperationWorkReservation> {
        val result = mutableListOf<OperationWorkReservation>()
        for (reservation in HarvestWorkClaims.kernel.inspect(npcUuid, tick)) {
            result.add(copy(OperationReservationKind.HARVEST_SITE, reservation))
        }
        for (reservation in ContainerStepReservations.inspect(npcUuid, tick)) {
            result.add(copy(OperationReservationKind.CONTAINER_STEP, reservation))
        }
        return java.util.List.copyOf(result)
    }

    private fun copy(kind: OperationReservationKind, reservation: SpatialWorkReservation): OperationWorkReservation {
        val timing = when (reservation) {
            is SpatialWorkReservation.Held -> OperationReservationTiming.Held(reservation.expiresAtTick)
            is SpatialWorkReservation.Requested ->
                OperationReservationTiming.Requested(reservation.firstRequestTick, reservation.lastRequestTick)
        }
        return OperationWorkReservation(kind, reservation.scopeId, reservation.dimensionId, reservation.anchor, timing)
    }
}
