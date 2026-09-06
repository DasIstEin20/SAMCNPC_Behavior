package io.samcnpc.behavior.lumberjack

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LumberjackNavigationHelperTest {
    @Test
    fun `cleared block arrival uses the floor position instead of its former centre`() {
        val clearedLog = NpcBlockPosition(10, 64, 10)
        val feetAtFormerBlockBase = NpcPosition(10.5, 64.0, 10.5)

        assertTrue(isWithinPosition(feetAtFormerBlockBase, clearedLog, 0.95))
        assertFalse(isWithinDistance(feetAtFormerBlockBase, clearedLog, 0.49))
    }

    @Test
    fun `stump distance ignores vertical jitter while retaining a horizontal safety bound`() {
        val stump = NpcBlockPosition(4, 70, -2)

        assertTrue(horizontalDistance(NpcPosition(4.6, 200.0, -1.6), stump) < 0.2)
        assertFalse(horizontalDistance(NpcPosition(5.7, 71.0, -1.5), stump) <= 1.05)
    }
}
