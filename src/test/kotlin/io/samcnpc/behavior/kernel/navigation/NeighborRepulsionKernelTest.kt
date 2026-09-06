package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NeighborRepulsionKernelTest {
    @Test
    fun `nearest worker selects the opposite side then rotates after workers separate`() {
        val kernel = NeighborRepulsionKernel()
        val npc = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val position = NpcPosition(0.5, 64.0, 0.5)
        val eastNeighbor = NpcPosition(1.5, 64.0, 0.5)

        val first = assertNotNull(kernel.nextTarget(npc, 0, position, listOf(eastNeighbor)) { true })
        val repeated = kernel.nextTarget(npc, 51, position, listOf(eastNeighbor)) { true }
        assertNull(repeated)

        kernel.nextTarget(npc, 52, position, emptyList()) { true }
        val second = assertNotNull(kernel.nextTarget(npc, 53, position, listOf(eastNeighbor)) { true })

        assertEquals(NeighborRepulsionKernel.Direction.WEST, first.direction)
        assertEquals(NeighborRepulsionKernel.Direction.NORTH, second.direction)
    }
}
