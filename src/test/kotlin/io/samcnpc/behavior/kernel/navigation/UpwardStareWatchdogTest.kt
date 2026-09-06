package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcPosition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpwardStareWatchdogTest {
    @Test
    fun `triggers on the 200th upward tick while height changes inside one horizontal 4x4 cell`() {
        val watchdog = UpwardStareWatchdog()

        repeat(199) { tick ->
            assertFalse(watchdog.record(NpcPosition(2.9, 64.0 + tick, -1.1), -25.0F))
        }

        assertTrue(watchdog.record(NpcPosition(2.9, 512.0, -1.1), -25.0F))
    }

    @Test
    fun `resets when the NPC looks forward or leaves its 4x4 horizontal cell`() {
        val watchdog = UpwardStareWatchdog()
        repeat(100) {
            assertFalse(watchdog.record(NpcPosition(0.1, 64.0, 0.1), -30.0F))
        }
        assertFalse(watchdog.record(NpcPosition(0.1, 64.0, 0.1), 0.0F))
        repeat(199) {
            assertFalse(watchdog.record(NpcPosition(4.1, 64.0, 0.1), -30.0F))
        }
        assertTrue(watchdog.record(NpcPosition(4.1, 64.0, 0.1), -30.0F))
    }
}
