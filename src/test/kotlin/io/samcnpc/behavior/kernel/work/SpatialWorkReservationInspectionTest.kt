package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class SpatialWorkReservationInspectionTest {
    private val first = UUID(0, 1)
    private val second = UUID(0, 2)
    private val task = UUID(1, 1)
    private val position = NpcBlockPosition(0, 64, 0)
    private val dimension = "minecraft:overworld"

    @Test fun aReadNeverArbitratesRenewsOrReleasesAndDoesNotRevealAnotherNpc() {
        val kernel = SpatialWorkClaimKernel(leaseTicks = 10)
        assertEquals(SpatialWorkClaimKernel.Result.Pending, kernel.renewOrClaim(first, dimension, position, 0, task))
        val queued = assertIs<SpatialWorkReservation.Requested>(kernel.inspect(first, 1).single())
        assertEquals(task, queued.scopeId)
        assertEquals(0L, queued.firstRequestTick)
        assertEquals(0L, queued.lastRequestTick)
        assertTrue(kernel.inspect(second, 1).isEmpty())
        // If inspection advanced the arbitration clock, this earlier request would clear the queue.
        assertEquals(SpatialWorkClaimKernel.Result.Pending, kernel.renewOrClaim(second, dimension, position, 0))
        assertIs<SpatialWorkClaimKernel.Result.Contested>(kernel.renewOrClaim(second, dimension, position, 1))
        val held = assertIs<SpatialWorkReservation.Held>(kernel.inspect(first, 1).single())
        assertEquals(11L, held.expiresAtTick)
        assertEquals(SpatialWorkClaimKernel.Result.Acquired, kernel.renewOrClaim(first, dimension, position, 2, task))
        assertEquals(11L, held.expiresAtTick)
        assertIs<SpatialWorkClaimKernel.Result.Contested>(kernel.renewOrClaim(second, dimension, position, 2))
        assertTrue(kernel.inspect(first, 12).isEmpty())
        assertEquals(SpatialWorkClaimKernel.Result.Acquired, kernel.renewOrClaim(second, dimension, position, 12))
        assertEquals(0L, queued.firstRequestTick)
    }

    @Test fun expiredRequestsAndReversedClockAreUnknownWithoutMutatingTheKernel() {
        val kernel = SpatialWorkClaimKernel(leaseTicks = 2)
        kernel.renewOrClaim(first, dimension, position, 5, task)
        assertTrue(kernel.inspect(first, 4).isEmpty())
        assertTrue(kernel.inspect(first, 8).isEmpty())
        assertIs<SpatialWorkReservation.Requested>(kernel.inspect(first, 5).single())
        assertEquals(SpatialWorkClaimKernel.Result.Acquired, kernel.renewOrClaim(first, dimension, position, 6, task))
        val held = assertIs<SpatialWorkReservation.Held>(kernel.inspect(first, 6).single())
        kernel.clear()
        assertTrue(kernel.inspect(first, 6).isEmpty())
        assertEquals(8L, held.expiresAtTick)
    }
}
