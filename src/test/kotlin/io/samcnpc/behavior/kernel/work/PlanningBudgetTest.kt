package io.samcnpc.behavior.kernel.work

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PlanningBudgetTest {
    @Test fun sixtyFourWorkersHaveBoundedAggregateSlicesAndEveryWaiterGetsATurn() {
        val ids = (1L..64L).map { UUID(0, it) }
        for (order in listOf(ids, ids.reversed())) {
            val budget = PlanningBudget(unitsPerTick = 8, maxSlice = 2)
            val accepted = mutableMapOf<UUID, Int>()
            for (tick in 0L..64L) {
                for (npc in order) if (budget.acquire(npc, tick, 2)) accepted[npc] = (accepted[npc] ?: 0) + 1
                val stats = budget.statistics()
                assertTrue(stats.reserved <= 8 && stats.consumed <= stats.reserved && stats.peakReserved <= 8)
                if (tick == 1L) assertEquals(ids.take(4).toSet(), accepted.keys)
                if (tick == 16L) assertEquals(ids.toSet(), accepted.keys)
            }
            assertTrue(accepted.values.all { it == 4 }); assertTrue(budget.statistics().deferredSlices > 0)
        }
    }
    @Test fun oversizedSliceNeverPartiallyConsumesAReservationAndReleaseCannotSpendTwice() {
        val npc = UUID(0, 1); val budget = PlanningBudget(unitsPerTick = 8, maxSlice = 8)
        assertFalse(budget.acquire(npc, 0, 4))
        assertFalse(budget.acquire(npc, 1, 5))
        assertEquals(0, budget.statistics().consumed); assertEquals(4, budget.statistics().reserved)
        assertTrue(budget.acquire(npc, 2, 5)); assertEquals(5, budget.statistics().consumed)
        assertFalse(budget.acquire(npc, 2, 1)); assertEquals(5, budget.statistics().consumed)
        budget.release(npc)
        assertFalse(budget.acquire(npc, 2, 1)); assertEquals(5, budget.statistics().consumed)
        assertFailsWith<IllegalArgumentException> { budget.acquire(npc, 2, 9) }
    }
    @Test fun expiredQueuedWorkAndBackwardClockCannotReuseOldTickets() {
        val budget = PlanningBudget(unitsPerTick = 4, maxSlice = 4, maxWaiters = 1)
        val first = UUID(0, 1); val other = UUID(0, 2)
        assertFalse(budget.acquire(first, 0, 4)); assertFalse(budget.acquire(other, 0, 4))
        assertFalse(budget.acquire(other, 41, 4))
        assertTrue(budget.acquire(other, 42, 4))
        assertFalse(budget.acquire(first, 0, 4))
        assertEquals(0, budget.statistics().consumed)
        budget.clear(); assertEquals(0, budget.statistics().waiting)
    }
    @Test fun observedCountsDescribeActualQueriesSeparatelyFromConservativeReservations() {
        val budget = PlanningBudget(); val npc = UUID(0, 1)
        budget.acquire(npc, 0, 100)
        assertTrue(budget.acquire(npc, 1, 100))
        repeat(12) { budget.observedQuery() }
        budget.advance(2)
        val stats = budget.statistics()
        assertEquals(100, stats.previousConsumed); assertEquals(12, stats.previousObservedQueries)
        assertEquals(12, stats.peakObservedQueries)
    }
    @Test fun simultaneousSmallReactionAndLargeWorkPlanningCannotConsumeEachOthersTickets() {
        val npc = UUID(0, 1); val budget = PlanningBudget(unitsPerTick = 8, maxSlice = 8)
        var work = 0; var reactions = 0
        for (tick in 0L..20L) {
            if (budget.acquire(npc, tick, 2, PlanningKind.REACTION_SEARCH)) reactions++
            if (budget.acquire(npc, tick, 8, PlanningKind.FOREST)) work++
            assertTrue(budget.statistics().consumed <= 8)
        }
        assertEquals(10, work); assertEquals(10, reactions)
        budget.release(npc); assertEquals(0, budget.statistics().waiting)
    }

}
