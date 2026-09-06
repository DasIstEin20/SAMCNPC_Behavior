package io.samcnpc.behavior.kernel.work

import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertIs

class SpatialWorkClaimKernelTest {
    @Test
    fun `nearby work zones are exclusive until their lease expires`() {
        val kernel = SpatialWorkClaimKernel(leaseTicks = 10, separationBlocks = 6.0)
        val first = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val second = UUID.fromString("223e4567-e89b-12d3-a456-426614174000")

        assertIs<SpatialWorkClaimKernel.Result.Acquired>(
            kernel.renewOrClaim(first, "minecraft:overworld", NpcBlockPosition(10, 64, 10), 0),
        )
        assertIs<SpatialWorkClaimKernel.Result.Contested>(
            kernel.renewOrClaim(second, "minecraft:overworld", NpcBlockPosition(14, 80, 10), 1),
        )
        assertIs<SpatialWorkClaimKernel.Result.Acquired>(
            kernel.renewOrClaim(second, "minecraft:overworld", NpcBlockPosition(14, 80, 10), 11),
        )
    }
}
