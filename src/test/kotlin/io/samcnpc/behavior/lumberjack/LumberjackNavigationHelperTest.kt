package io.samcnpc.behavior.lumberjack

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LumberjackNavigationHelperTest {
    @Test
    fun `the observed rising body beside a stump is not a completed landing`() {
        val stump = NpcBlockPosition(24, -60, 0)
        val falseLanding = NpcPosition(23.45028, -58.99866, 0.49764)
        assertFalse(isStandingOnPreservedStump(falseLanding, false, stump))
        assertFalse(isStandingOnPreservedStump(falseLanding, true, stump))
    }

    @Test
    fun `even a centered jump must land before stump work begins`() {
        val stump = NpcBlockPosition(24, -60, 0)
        assertFalse(isStandingOnPreservedStump(NpcPosition(24.5, -59.0, 0.5), false, stump))
        assertTrue(isStandingOnPreservedStump(NpcPosition(24.5, -59.0, 0.5), true, stump))
    }

    @Test
    fun `grounded edge overlap is usable but a higher canopy is not the stump`() {
        val stump = NpcBlockPosition(24, -60, 0)
        assertTrue(isStandingOnPreservedStump(NpcPosition(23.85, -59.0, 0.5), true, stump))
        assertFalse(isStandingOnPreservedStump(NpcPosition(24.5, -58.0, 0.5), true, stump))
    }

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
