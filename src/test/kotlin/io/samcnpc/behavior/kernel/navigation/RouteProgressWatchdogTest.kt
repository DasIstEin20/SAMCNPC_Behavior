package io.samcnpc.behavior.kernel.navigation

import io.samcnpc.core.api.NpcPosition
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteProgressWatchdogTest {
    private val npc = UUID(0L, 1L)
    private val start = NpcPosition(0.0, 0.0, 0.0)
    private val goal = NpcPosition(10.0, 0.0, 0.0)

    @Test
    fun duplicateObservationsCannotConsumeTheWorldTickTimeout() {
        val watchdog = RouteProgressWatchdog<String>(0.1)
        repeat(100) { assertFalse(watchdog.hasStalled(npc, "route", start, goal, 20, 100)) }
        assertEquals(0, watchdog.status(npc)?.noProgressTicks)
        assertFalse(watchdog.hasStalled(npc, "route", start, goal, 20, 119))
        assertTrue(watchdog.hasStalled(npc, "route", start, goal, 20, 120))
    }

    @Test
    fun infrequentDecisionsStillCountActualElapsedWorldTicks() {
        val watchdog = RouteProgressWatchdog<String>(0.1)
        assertFalse(watchdog.hasStalled(npc, "route", start, goal, 20, 100))
        assertTrue(watchdog.hasStalled(npc, "route", start, goal, 20, 125))
        assertEquals(25, watchdog.status(npc)?.noProgressTicks)
    }

    @Test
    fun collisionAndVerticalOscillationCannotRenewTheDeadline() {
        val watchdog = RouteProgressWatchdog<String>(0.1)
        assertFalse(watchdog.hasStalled(npc, "route", start, goal, 20, 100))
        for (tick in 101L..119L) {
            val position = NpcPosition(if (tick % 2L == 0L) 0.001 else -0.001, (tick % 3).toDouble(), 0.0)
            assertFalse(watchdog.hasStalled(npc, "route", position, goal, 20, tick))
        }
        assertTrue(watchdog.hasStalled(npc, "route", start, goal, 20, 120))
    }

    @Test
    fun meaningfulBestDistanceImprovementRestartsTheDeadlineOnce() {
        val watchdog = RouteProgressWatchdog<String>(0.1)
        assertFalse(watchdog.hasStalled(npc, "route", start, goal, 20, 100))
        val improved = start.copy(x = 1.0)
        assertFalse(watchdog.hasStalled(npc, "route", improved, goal, 20, 115))
        // Moving away and back to the same best position cannot count the same progress again.
        assertFalse(watchdog.hasStalled(npc, "route", start, goal, 20, 130))
        assertTrue(watchdog.hasStalled(npc, "route", improved, goal, 20, 135))
    }

    @Test
    fun aDifferentRouteOrResetWorldClockStartsAnIndependentMeasurement() {
        val watchdog = RouteProgressWatchdog<String>(0.1)
        watchdog.hasStalled(npc, "route", start, goal, 20, 100)
        assertFalse(watchdog.hasStalled(npc, "return", start, goal, 20, 119))
        assertFalse(watchdog.hasStalled(npc, "return", start, goal, 20, 1))
        assertTrue(watchdog.hasStalled(npc, "return", start, goal, 20, 21))
        watchdog.clear(npc)
        assertEquals(null, watchdog.status(npc))
    }
}
