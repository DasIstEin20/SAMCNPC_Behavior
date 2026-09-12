package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class SpatialWorkClaimKernelTest {
    private val first = UUID(0, 1)
    private val second = UUID(0, 2)
    private val third = UUID(0, 3)
    private val position = NpcBlockPosition(10, 64, 10)
    @Test fun nearbyWorkZonesStayExclusiveUntilTheirLeaseExpires() {
        val kernel = SpatialWorkClaimKernel(leaseTicks = 10, separationBlocks = 6.0)
        assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(first, "minecraft:overworld", position, 0))
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(first, "minecraft:overworld", position, 1))
        assertIs<SpatialWorkClaimKernel.Result.Contested>(kernel.renewOrClaim(second, "minecraft:overworld", NpcBlockPosition(14, 80, 10), 1))
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(second, "minecraft:overworld", NpcBlockPosition(14, 80, 10), 11))
    }
    @Test fun collectionOrderCannotChooseTheWinnerAndReleasedWorkerCannotJumpOlderWaiters() {
        for (order in listOf(listOf(first, second, third), listOf(third, second, first), listOf(second, first, third))) {
            val kernel = SpatialWorkClaimKernel(leaseTicks = 10, separationBlocks = 0.0)
            for (npc in order) assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(npc, "minecraft:overworld", position, 0))
            assertIs<SpatialWorkClaimKernel.Result.Contested>(kernel.renewOrClaim(third, "minecraft:overworld", position, 1))
            assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(first, "minecraft:overworld", position, 1))
            assertIs<SpatialWorkClaimKernel.Result.Contested>(kernel.renewOrClaim(second, "minecraft:overworld", position, 1))
            kernel.release(first)
            assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(first, "minecraft:overworld", position, 1))
            assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(second, "minecraft:overworld", position, 2))
            kernel.release(second)
            assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(second, "minecraft:overworld", position, 2))
            assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(third, "minecraft:overworld", position, 3))
            assertIs<SpatialWorkClaimKernel.Result.Contested>(kernel.renewOrClaim(first, "minecraft:overworld", position, 3))
        }
    }
    @Test fun dimensionExactHeightTaskIdentityAndStaleReleaseCannotAliasAReservation() {
        val kernel = SpatialWorkClaimKernel(separationBlocks = 0.0)
        val oldTask = UUID(1, 1); val newTask = UUID(1, 2)
        kernel.renewOrClaim(first, "minecraft:overworld", position, 0, oldTask)
        kernel.renewOrClaim(second, "minecraft:the_nether", position, 0)
        kernel.renewOrClaim(third, "minecraft:overworld", position.copy(y = 65), 0)
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(first, "minecraft:overworld", position, 1, oldTask))
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(second, "minecraft:the_nether", position, 1))
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(third, "minecraft:overworld", position.copy(y = 65), 1))
        assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(first, "minecraft:overworld", position, 2, newTask))
        kernel.release(first, oldTask)
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(first, "minecraft:overworld", position, 3, newTask))
        kernel.release(first, oldTask)
        assertIs<SpatialWorkClaimKernel.Result.Held>(kernel.renewOrClaim(first, "minecraft:overworld", position, 4, newTask))
    }
    @Test fun boundedQueuesExpireAndBackwardClockOrClearDiscardOldLeases() {
        val kernel = SpatialWorkClaimKernel(leaseTicks = 2, maxEntries = 1)
        kernel.renewOrClaim(first, "minecraft:overworld", position, 0)
        assertIs<SpatialWorkClaimKernel.Result.Limited>(kernel.renewOrClaim(second, "minecraft:overworld", position, 0))
        assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(second, "minecraft:overworld", position, 4))
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(kernel.renewOrClaim(second, "minecraft:overworld", position, 5))
        assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(first, "minecraft:overworld", position, 0))
        kernel.clear()
        assertIs<SpatialWorkClaimKernel.Result.Pending>(kernel.renewOrClaim(second, "minecraft:overworld", position, 0))
    }
    @Test fun neighboringCollectorsUseNearestActiveWorkAnchorAndStableTies() {
        val kernel = SpatialWorkClaimKernel()
        val other = position.copy(z = 16)
        kernel.renewOrClaim(second, "minecraft:overworld", other, 0)
        kernel.renewOrClaim(first, "minecraft:overworld", position, 0)
        kernel.renewOrClaim(first, "minecraft:overworld", position, 1)
        kernel.renewOrClaim(second, "minecraft:overworld", other, 1)
        val firstFilter = kernel.collectionFilter(first, first, "minecraft:overworld", 1)
        val secondFilter = kernel.collectionFilter(second, second, "minecraft:overworld", 1)
        val own = io.samcnpc.core.api.NpcPosition(10.5, 65.0, 10.5)
        val neighbor = own.copy(z = 16.5); val middle = own.copy(z = 13.5)
        assertTrue(firstFilter(own)); assertFalse(firstFilter(neighbor)); assertTrue(firstFilter(middle))
        assertFalse(secondFilter(own)); assertTrue(secondFilter(neighbor)); assertFalse(secondFilter(middle))
        assertFalse(kernel.collectionFilter(first, UUID(8, 8), "minecraft:overworld", 1)(own))
        kernel.release(second)
        assertTrue(kernel.collectionFilter(first, first, "minecraft:overworld", 1)(neighbor))
    }

}
