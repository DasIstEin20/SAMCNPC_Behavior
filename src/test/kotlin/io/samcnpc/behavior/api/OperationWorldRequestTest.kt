package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperationWorldRequestTest {
    @Test
    fun suppliedCellsAreDetachedBoundedAndUnique() {
        val cells = mutableListOf(NpcBlockPosition(1, 2, 3))
        val request = OperationWorldRequest(entities = null, blocks = cells)
        cells.clear()
        assertEquals(listOf(NpcBlockPosition(1, 2, 3)), request.blocks)
        assertThrows(UnsupportedOperationException::class.java) { (request.blocks as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) {
            OperationWorldRequest(blocks = List(17) { NpcBlockPosition(it, 0, 0) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationWorldRequest(blocks = List(2) { NpcBlockPosition(1, 2, 3) })
        }
    }
}
